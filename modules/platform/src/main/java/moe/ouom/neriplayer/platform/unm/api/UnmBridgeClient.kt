package moe.ouom.neriplayer.platform.unm.api

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.io.readBytesLimited
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * One audio address produced by the self-hosted UnblockNeteaseMusic bridge.
 *
 * [source] is the upstream provider that answered, never a user-visible brand: the bridge only
 * resolves by NetEase song id, so the recording identity stays the one the caller asked for.
 */
data class UnmMatchedAudio(
    val neteaseId: String,
    val source: String,
    val url: String,
    val bitrateKbps: Int,
    val sizeBytes: Long,
    val durationMs: Long,
    val mimeType: String,
    val md5: String?
)

/** Distinct reasons so the caller can tell "service not configured" from "service said no". */
enum class UnmBridgeFailure { UNCONFIGURED, UNREACHABLE, NOT_FOUND, REJECTED }

sealed interface UnmBridgeOutcome {
    data class Matched(val audio: UnmMatchedAudio) : UnmBridgeOutcome

    data class Failed(val reason: UnmBridgeFailure) : UnmBridgeOutcome
}

/**
 * Client for the local UnblockNeteaseMusic matching bridge (`GET {base}/match?id=<neteaseId>`).
 *
 * Contract of the bridge response:
 * `{"ok":true,"id":"<neteaseId>","source":"<provider>","url":"<audio>","size":<bytes>,"br":<bitrate>,"md5":"<md5|null>"}`
 * or `{"ok":false,"reason":"<reason>"}`.
 *
 * The bridge is a thin wrapper around UnblockNeteaseMusic's `src/provider/match.js`, which reports
 * only a byte size and a bitrate for the address it found. Both values are therefore the *only*
 * available evidence of the recording length: [parseUnmBridgeMatch] converts them into a duration
 * and compares it with the expected track length, so a truncated or mismatched stream never reaches
 * the player. Nothing else from upstream is trusted, and no platform cookie is ever sent here.
 */
class UnmBridgeClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val requests = ArrayDeque<Long>()

    @Synchronized private fun acquire(): Boolean {
        val timestamp = now()
        while (requests.isNotEmpty() && timestamp - requests.first >= RATE_WINDOW_MS) requests.removeFirst()
        if (requests.size >= RATE_MAX_REQUESTS) return false
        requests.addLast(timestamp)
        return true
    }

    /**
     * @param sources optional explicit provider list; empty means "let the bridge use its own tier
     * order", which is how the preferred 320 kb/s providers are picked before the 128 kb/s fallback.
     */
    suspend fun match(
        bridgeBaseUrl: String,
        neteaseId: String,
        expectedDurationMs: Long? = null,
        sources: List<String> = emptyList()
    ): UnmBridgeOutcome {
        if (neteaseId.toLongOrNull()?.takeIf { it > 0 }?.toString() != neteaseId) {
            return UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED)
        }
        val base = parseUnmBridgeBaseUrl(bridgeBaseUrl)
            ?: return UnmBridgeOutcome.Failed(UnmBridgeFailure.UNCONFIGURED)
        if (!acquire()) return UnmBridgeOutcome.Failed(UnmBridgeFailure.UNREACHABLE) // no queue, no retry storm
        val requested = sources.filter { it in UNM_ALLOWED_SOURCES }
        val endpoint = base.newBuilder()
            .addPathSegment("match")
            .addQueryParameter("id", neteaseId)
            .apply { if (requested.isNotEmpty()) addQueryParameter("sources", requested.joinToString(",")) }
            .build()
        val outcome = withTimeoutOrNull(RESPONSE_TIMEOUT_MS) {
            try {
                client.newCall(
                    Request.Builder()
                        .url(endpoint)
                        .header("User-Agent", "UniMusic/1.0 (UnblockNeteaseMusic bridge)")
                        .header("Accept", "application/json")
                        .build()
                ).awaitResponse { response ->
                    when {
                        response.code == 404 -> UnmBridgeOutcome.Failed(UnmBridgeFailure.NOT_FOUND)
                        !response.isSuccessful -> UnmBridgeOutcome.Failed(UnmBridgeFailure.UNREACHABLE)
                        else -> parseUnmBridgeMatch(
                            response.body.byteStream().readBytesLimited(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8),
                            expectedId = neteaseId,
                            expectedDurationMs = expectedDurationMs
                        )
                    }
                }
            } catch (_: IOException) {
                UnmBridgeOutcome.Failed(UnmBridgeFailure.UNREACHABLE)
            }
        }
        return outcome ?: UnmBridgeOutcome.Failed(UnmBridgeFailure.UNREACHABLE)
    }

    /** Reachability check for the settings entry; returns the bridge version string when it answers. */
    suspend fun probe(bridgeBaseUrl: String): String? {
        val base = parseUnmBridgeBaseUrl(bridgeBaseUrl) ?: return null
        val endpoint = base.newBuilder().addPathSegment("health").build()
        return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            try {
                client.newCall(
                    Request.Builder().url(endpoint).header("Accept", "application/json").build()
                ).awaitResponse { response ->
                    if (!response.isSuccessful) return@awaitResponse null
                    val raw = response.body.byteStream().readBytesLimited(8 * 1024L).toString(Charsets.UTF_8)
                    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@awaitResponse null
                    if (!root.optBoolean("ok", false)) return@awaitResponse null
                    root.optString("version").ifEmpty { "unknown" }
                }
            } catch (_: IOException) {
                null
            }
        }
    }

    private companion object {
        const val RATE_WINDOW_MS = 300_000L
        const val RATE_MAX_REQUESTS = 60
        const val RESPONSE_TIMEOUT_MS = 3_500L
        const val PROBE_TIMEOUT_MS = 2_500L
        const val MAX_RESPONSE_BYTES = 32 * 1024L
    }
}

/**
 * Providers the bridge may report. `bilibili`/`bilivideo` are excluded on purpose: the video-track
 * route returns a short clip, and the audio route resolves a different id space, so neither can be
 * treated as the full original recording the caller asked for.
 */
internal val UNM_ALLOWED_SOURCES = setOf("pyncmd", "bodian", "kuwo")

/** Accepts an http(s) base URL for a user-hosted bridge: LAN addresses and explicit ports are fine. */
internal fun parseUnmBridgeBaseUrl(raw: String): HttpUrl? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val url = trimmed.toHttpUrlOrNull() ?: return null
    if (url.scheme != "http" && url.scheme != "https") return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
    if (url.queryParameterNames.isNotEmpty()) return null
    return url
}

/** Byte size plus bitrate is the only length evidence the bridge has; ±3% absorbs tag and header overhead. */
internal fun unmDurationToleranceMs(expectedDurationMs: Long): Long =
    maxOf(4_000L, expectedDurationMs / 32)

internal fun parseUnmBridgeMatch(
    raw: String,
    expectedId: String,
    expectedDurationMs: Long?
): UnmBridgeOutcome {
    val root = runCatching { JSONObject(raw) }.getOrNull()
        ?: return UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED)
    if (!root.optBoolean("ok", false)) return UnmBridgeOutcome.Failed(UnmBridgeFailure.NOT_FOUND)
    val id = root.optString("id")
    if (id.isNotEmpty() && id != expectedId) return UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED)
    val source = root.optString("source")
    if (source !in UNM_ALLOWED_SOURCES) return UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED)
    val bitrate = root.optInt("br", 0)
    if (bitrate !in setOf(128_000, 192_000, 320_000)) return UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED)
    val size = root.optLong("size", 0L)
    if (size < MIN_AUDIO_BYTES) return UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED)
    val url = root.optString("url").toHttpUrlOrNull()
        ?: return UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED)
    if (!unmAudioUrlAllowed(url)) return UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED)
    // size bytes at `bitrate` bits/s: bytes * 8 / (bits per ms) where bits per ms = bitrate / 1000.
    val durationMs = size * 8_000L / bitrate
    if (expectedDurationMs != null && expectedDurationMs > 0L &&
        abs(durationMs - expectedDurationMs) > unmDurationToleranceMs(expectedDurationMs)
    ) {
        return UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED)
    }
    val md5 = root.optString("md5").takeIf { it.length == 32 && it.all { char -> char.isDigit() || char in 'a'..'f' || char in 'A'..'F' } }
    return UnmBridgeOutcome.Matched(
        UnmMatchedAudio(
            neteaseId = expectedId,
            source = source,
            url = url.toString(),
            bitrateKbps = bitrate / 1000,
            sizeBytes = size,
            durationMs = durationMs,
            mimeType = "audio/mpeg",
            md5 = md5
        )
    )
}

/**
 * NetEase CDN answers only over https; the Kuwo CDN endpoints that the `bodian`/`kuwo` providers
 * return redirect over plain http and are still inside the cleartext-permitted network config.
 */
internal fun unmAudioUrlAllowed(url: HttpUrl): Boolean {
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
    val path = url.encodedPath
    if (path.endsWith(".m3u8", ignoreCase = true) || path.endsWith(".mpd", ignoreCase = true)) return false
    val kuwoHost = url.host.endsWith(".kuwo.cn")
    val neteaseHost = url.host.endsWith(".music.126.net")
    return when (url.scheme) {
        "https" -> kuwoHost || neteaseHost
        "http" -> kuwoHost
        else -> false
    }
}

internal const val MIN_AUDIO_BYTES = 512L * 1024L
