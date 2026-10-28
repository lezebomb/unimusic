package moe.ouom.neriplayer.platform.kuwo.api

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.common.io.readBytesLimited
import moe.ouom.neriplayer.data.model.unified.SourceCheckStatus
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.search.api.client.CatalogAudioResolution
import moe.ouom.neriplayer.platform.search.api.client.catalogMediaUrl
import moe.ouom.neriplayer.platform.search.api.client.decodeCatalogMetadataText
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Official web flow: anonymous session -> current metadata/permission -> normal playUrl.
 * The session token rotates on each response, so capture/sign/update under one mutex.
 * Never uses a car client, VIP flags, encrypted media or a third-party account service.
 */
class KuwoAudioClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(4, TimeUnit.SECONDS).followRedirects(false).build()) {
    private val lock = Mutex()
    private var token: Cookie? = null

    suspend fun resolve(id: String): CatalogAudioResolution = lock.withLock {
        require(id.toLongOrNull()?.takeIf { it > 0 }?.toString() == id)
        try {
            resolveInSession(id)
        } catch (rejected: KuwoWebSessionRejected) {
            // One normal anonymous refresh only; the second rejection propagates.
            if (token == null) throw rejected
            token = null
            resolveInSession(id)
        }
    }

    private suspend fun resolveInSession(id: String): CatalogAudioResolution {
        if (token == null || token!!.expiresAt <= System.currentTimeMillis()) request("/")
        val detail = request("/api/www/music/musicInfo", id)
        val data = detail.optJSONObject("data")
        if (detail.optInt("code") != 200 || data == null) return CatalogAudioResolution(SourceCheckStatus.UNAVAILABLE)
        val metadata = parseKuwoAudioMetadata(data, id)
            ?: return CatalogAudioResolution(SourceCheckStatus.METADATA_MISMATCH)
        // Missing permission evidence fails closed; PAY/COPYRIGHT search flags are not entitlement.
        if (!data.has("isListenFee") || data.optBoolean("isListenFee", true) || data.optInt("online") != 1 ||
            data.optJSONObject("payInfo")?.optInt("cannotOnlinePlay", 1) != 0) {
            return CatalogAudioResolution(SourceCheckStatus.NO_PERMISSION, metadata)
        }
        val play = request("/api/v1/www/music/playUrl", id)
        val url = play.optJSONObject("data")?.optString("url").orEmpty()
        val safe = catalogMediaUrl(url, setOf("kuwo.cn"))
        return CatalogAudioResolution(if (play.optInt("code") == 200 && safe != null) SourceCheckStatus.ADDRESS_RESOLVED
            else SourceCheckStatus.UNAVAILABLE, metadata, safe.takeIf { play.optInt("code") == 200 })
    }

    private suspend fun request(path: String, id: String? = null): JSONObject {
        val url = "https://www.kuwo.cn$path".toHttpUrl().newBuilder().apply {
            if (id != null) {
                addQueryParameter("mid", id); addQueryParameter("type", "music")
                addQueryParameter("httpsStatus", "1"); addQueryParameter("reqId", UUID.randomUUID().toString())
                addQueryParameter("plat", "web_www")
            }
        }.build()
        val request = Request.Builder().url(url).header("Referer", "https://www.kuwo.cn/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
        token?.let { cookie ->
            request.header("Cookie", "${cookie.name}=${cookie.value}")
            if (id != null) request.header("Secret", kuwoWebSessionHeader(cookie.value))
        }
        val (nextToken, payload) = client.newCall(request.build()).awaitResponse { response ->
            if (id != null && response.code in setOf(401, 403)) throw KuwoWebSessionRejected(response.code)
            if (!response.isSuccessful) throw IOException("Kuwo HTTP ${response.code}")
            val cookie = Cookie.parseAll(url, response.headers).firstOrNull { it.name == KUWO_SESSION_COOKIE }
            val raw = response.body.byteStream().readBytesLimited(512 * 1024L).toString(Charsets.UTF_8)
            cookie to if (id == null) JSONObject() else JSONObject(raw)
        }
        // Only the coroutine owning the mutex can commit session state. A cancelled OkHttp
        // callback may finish parsing later, but must never overwrite a newer request's token.
        currentCoroutineContext().ensureActive()
        nextToken?.let { token = it }
        return payload
    }
}

private class KuwoWebSessionRejected(status: Int) : IOException("Kuwo web session HTTP $status")

internal const val KUWO_SESSION_COOKIE = "Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324"

/** Web request header, not audio decryption. Mirrors JS Number rounding and decimal folding. */
internal fun kuwoWebSessionHeader(token: String, salt: Int = Random.nextInt(10_000_000, 100_000_000)): String {
    require(token.isNotEmpty() && token.length <= 512 && salt in 10_000_000..99_999_999)
    val key = KUWO_SESSION_COOKIE
    var digits = key.map { it.code.toString() }.joinToString("")
    val step = digits.length / 5
    val multiplier = (1..5).joinToString("") { digits.getOrNull(step * it)?.toString().orEmpty() }.toLong()
    digits += salt.toString()
    fun jsInt(text: String) = text.takeWhile { it in '0'..'9' }.toDouble()
    while (digits.length > 10) {
        val number = jsInt(digits.take(10)) + jsInt(digits.drop(10))
        digits = if (number < 1e21) number.toLong().toString()
            else number.toString().replace("E", "e+").replace("e+-", "e-")
    }
    val modulus = 2_147_483_647L
    val increment = ceil(key.length / 2.0).toLong()
    var state = (multiplier * digits.toLong() + increment) % modulus
    return buildString {
        token.forEach {
            append((it.code xor floor(state.toDouble() / modulus * 255).toInt()).toString(16).padStart(2, '0'))
            state = (multiplier * state + increment) % modulus
        }
        append(salt.toString(16).padStart(8, '0'))
    }
}

internal fun parseKuwoAudioMetadata(data: JSONObject, id: String): UnifiedSongMetadata? {
    if (data.optString("rid") != id) return null
    val title = decodeCatalogMetadataText(data.optString("name"))
    val artist = decodeCatalogMetadataText(data.optString("artist"))
    val duration = data.optLong("duration").takeIf { it in 1..604_800 } ?: return null
    if (title.isEmpty() || artist.isEmpty()) return null
    return UnifiedSongMetadata(title, artist, decodeCatalogMetadataText(data.optString("album")), duration * 1000)
}
