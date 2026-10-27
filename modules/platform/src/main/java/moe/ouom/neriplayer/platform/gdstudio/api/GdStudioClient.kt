package moe.ouom.neriplayer.platform.gdstudio.api

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.io.readBytesLimited
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class GdStudioAudio(val url: String, val bitrateKbps: Int, val mimeType: String)

/** Public developer API, used only after explicit source selection. Never receives platform cookies. */
class GdStudioClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(4, TimeUnit.SECONDS).followRedirects(false).build(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val requests = ArrayDeque<Long>()

    @Synchronized private fun acquire(): Boolean {
        val timestamp = now()
        while (requests.isNotEmpty() && timestamp - requests.first >= 300_000) requests.removeFirst()
        if (requests.size >= 50) return false
        requests.addLast(timestamp)
        return true
    }

    suspend fun resolveNetease(id: String): GdStudioAudio? {
        require(id.toLongOrNull()?.takeIf { it > 0 }?.toString() == id)
        if (!acquire()) return null // No waiting queue and no rate-limit retry storm.
        val url = "https://music-api.gdstudio.xyz/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("types", "url").addQueryParameter("source", "netease")
            .addQueryParameter("id", id).addQueryParameter("br", "320").build()
        return withTimeoutOrNull(4_000) {
            client.newCall(Request.Builder().url(url).header("User-Agent", "UniMusic/1.0 (GD Studio public API)")
                .header("Accept", "application/json").build()).awaitResponse { response ->
                if (!response.isSuccessful) throw IOException("GD Studio HTTP ${response.code}")
                parseGdStudioAudio(response.body.byteStream().readBytesLimited(64 * 1024L).toString(Charsets.UTF_8))
            }
        }
    }
}

internal fun parseGdStudioAudio(raw: String): GdStudioAudio? {
    val root = JSONObject(raw)
    val bitrate = root.optInt("br", 0)
    if (bitrate !in setOf(128, 192, 320)) return null
    val url = root.optString("url").toHttpUrlOrNull() ?: return null
    // This adapter resolves NetEase IDs only. Do not accept a proxy, a credentialed URL or a playlist.
    if (url.scheme != "https" || !url.host.endsWith(".music.126.net") || url.port != 443 ||
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
        !url.encodedPath.endsWith(".mp3", ignoreCase = true)) return null
    // The service's size field disagrees with the documented unit in observed responses.
    // Do not use it as a trusted byte length or as evidence of full-length audio.
    return GdStudioAudio(url.toString(), bitrate, "audio/mpeg")
}
