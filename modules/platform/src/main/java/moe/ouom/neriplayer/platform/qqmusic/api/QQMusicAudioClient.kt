package moe.ouom.neriplayer.platform.qqmusic.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import moe.ouom.neriplayer.common.io.readBytesLimited
import moe.ouom.neriplayer.data.model.unified.*
import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicCredential
import moe.ouom.neriplayer.platform.search.api.client.CatalogAudioResolution
import moe.ouom.neriplayer.platform.search.api.client.catalogMediaUrl
import moe.ouom.neriplayer.platform.search.api.client.decodeCatalogMetadataText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class QQMusicAudioClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(4, TimeUnit.SECONDS).followRedirects(false).build()) : MusicCatalogProvider {
    override val providerId = "qqmusic"
    override val playbackMode = SourcePlaybackMode.AUTHORIZED_AUDIO

    override suspend fun search(query: String, cursor: String?, limit: Int): CatalogSearchPage {
        require(query.isNotBlank() && query.length <= 256 && limit in 1..50)
        val page = cursor?.toIntOrNull()?.takeIf { it > 0 && it.toString() == cursor }
            ?: if (cursor == null) 1 else throw IllegalArgumentException("Invalid QQ cursor")
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json").addQueryParameter("n", limit.toString())
            .addQueryParameter("p", page.toString()).addQueryParameter("w", query)
            .addQueryParameter("cr", "1").addQueryParameter("g_tk", "5381").build()
        val root = client.newCall(Request.Builder().url(url).header("Referer", "https://y.qq.com/").build()).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("QQ search HTTP ${response.code}")
            JSONObject(response.body.byteStream().readBytesLimited(2 * 1024 * 1024L).toString(Charsets.UTF_8))
        }
        if (root.optInt("code", -1) != 0) throw IOException("QQ search unavailable")
        val data = root.optJSONObject("data") ?: throw IOException("QQ search unavailable")
        val song = data.optJSONObject("song")
        val rows = song?.optJSONArray("list") ?: JSONArray()
        val tracks = buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val mid = row.optString("songmid")
                row.put("mid", mid).put("title", row.optString("songname"))
                    .put("album", JSONObject().put("name", row.optString("albumname")))
                parseQQAudioMetadata(row, mid)?.let { add(CatalogTrack(SongSourceKey(providerId, mid), it,
                    "https://y.qq.com/n/ryqq/songDetail/$mid", playbackMode)) }
            }
        }
        val total = song?.optLong("totalnum").takeIf { it != null && it >= 0 }
        return CatalogSearchPage(tracks, (page + 1).takeIf { page < Int.MAX_VALUE && rows.length() >= limit }?.toString(), total)
    }

    suspend fun resolve(mid: String, credential: QQMusicCredential?): CatalogAudioResolution {
        require(mid.matches(Regex("[A-Za-z0-9]{14}")))
        val detail = rpc("detail", "music.pf_song_detail_svr", "get_song_detail_yqq", JSONObject().put("song_mid", mid), credential)
        if (detail.optInt("code", -1) != 0) return CatalogAudioResolution(SourceCheckStatus.UNAVAILABLE)
        val data = detail.optJSONObject("data")
        val track = data?.optJSONObject("track_info") ?: data?.optJSONObject("trackInfo")
            ?: return CatalogAudioResolution(SourceCheckStatus.UNAVAILABLE)
        val metadata = parseQQAudioMetadata(track, mid) ?: return CatalogAudioResolution(SourceCheckStatus.METADATA_MISMATCH)
        val media = track.optJSONObject("file")?.optString("media_mid")?.takeIf { it.matches(Regex("[A-Za-z0-9]{14}")) }
            ?: return CatalogAudioResolution(SourceCheckStatus.UNAVAILABLE, metadata)
        val filename = "M500$media.mp3" // Full standard MP3 only; no RS02 preview or EVkey/ekey decryption.
        val audio = rpc("audio", "music.vkey.GetVkey", "UrlGetVkey", JSONObject()
            .put("uin", credential?.accountId ?: "0").put("guid", Random.nextLong(1_000_000_000, 10_000_000_000).toString())
            .put("filename", JSONArray().put(filename)).put("songmid", JSONArray().put(mid))
            .put("songtype", JSONArray().put(0)).put("ctx", 0), credential)
        return parseQQAudioResponse(audio, mid, filename, metadata, credential != null)
    }

    private suspend fun rpc(key: String, module: String, method: String, params: JSONObject,
        credential: QQMusicCredential? = null): JSONObject {
        val comm = JSONObject().put("ct", 24).put("cv", 0).put("format", "json").put("uin", credential?.accountId ?: "0")
        credential?.let { comm.put("authst", it.musicKey).put("tmeLoginType", it.loginType) }
        val payload = JSONObject().put("comm", comm).put(key, JSONObject().put("module", module).put("method", method).put("param", params))
        val request = Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg").header("Referer", "https://y.qq.com/")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        credential?.let { request.header("Cookie", it.cookieHeader()) }
        return client.newCall(request.build()).awaitResponse { response ->
            if (!response.isSuccessful) throw IOException("QQ Music HTTP ${response.code}")
            val root = JSONObject(response.body.byteStream().readBytesLimited(2 * 1024 * 1024L).toString(Charsets.UTF_8))
            if (root.optInt("code", -1) != 0) throw IOException("QQ Music unavailable")
            root.optJSONObject(key) ?: throw IOException("QQ Music missing response")
        }
    }
}

internal fun parseQQAudioMetadata(row: JSONObject, mid: String): UnifiedSongMetadata? {
    if (!mid.matches(Regex("[A-Za-z0-9]{14}")) || row.optString("mid") != mid) return null
    val title = decodeCatalogMetadataText(row.optString("title").ifBlank { row.optString("name") })
    val singers = row.optJSONArray("singer") ?: return null
    val artist = (0 until singers.length()).mapNotNull { singers.optJSONObject(it)?.optString("name")?.let(::decodeCatalogMetadataText)?.takeIf(String::isNotBlank) }.joinToString("/")
    val duration = row.optLong("interval").takeIf { it in 1..604_800 } ?: return null
    if (title.isBlank() || artist.isBlank()) return null
    // A descriptive soundtrack subtitle is not part of the title. Version evidence must survive.
    val subtitle = row.optString("subtitle").takeIf {
        it.isNotBlank() && RecordingMatchPolicy.versions(RecordingMetadata("", "", 0, versionContext = it)).isNotEmpty()
    }
    return UnifiedSongMetadata(if (subtitle == null) title else "$title ($subtitle)", artist,
        decodeCatalogMetadataText(row.optJSONObject("album")?.optString("name").orEmpty()), duration * 1000)
}

internal fun parseQQAudioResponse(response: JSONObject, mid: String, filename: String,
    metadata: UnifiedSongMetadata, signedIn: Boolean): CatalogAudioResolution {
    val rows = response.optJSONObject("data")?.optJSONArray("midurlinfo")
    val row = rows?.let { (0 until it.length()).mapNotNull(it::optJSONObject).singleOrNull { entry ->
        entry.optString("songmid") == mid && entry.optString("filename") == filename } }
    val code = row?.optInt("result", -1) ?: response.optInt("code", -1)
    val status = when {
        response.optInt("code", -1) in setOf(1000, 104400, 104401) -> SourceCheckStatus.AUTH_REQUIRED
        response.optInt("code", -1) != 0 -> SourceCheckStatus.UNAVAILABLE
        code == 104003 -> if (signedIn) SourceCheckStatus.NO_PERMISSION else SourceCheckStatus.AUTH_REQUIRED
        code != 0 || row == null -> SourceCheckStatus.UNAVAILABLE
        row.optString("ekey").isNotBlank() -> SourceCheckStatus.EXTERNAL_PLAYER_REQUIRED
        else -> SourceCheckStatus.ADDRESS_RESOLVED
    }
    if (status != SourceCheckStatus.ADDRESS_RESOLVED) return CatalogAudioResolution(status, metadata)
    val path = row!!.optString("purl")
    val url = if (path.startsWith(filename + "?")) catalogMediaUrl("https://isure.stream.qqmusic.qq.com/$path", setOf("qqmusic.qq.com")) else null
    return CatalogAudioResolution(if (url == null) SourceCheckStatus.UNAVAILABLE else status, metadata, url)
}
