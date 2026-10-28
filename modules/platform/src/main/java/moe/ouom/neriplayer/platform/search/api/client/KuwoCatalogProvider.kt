package moe.ouom.neriplayer.platform.search.api.client

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.io.readBytesLimited
import moe.ouom.neriplayer.common.search.normalizeFederatedQuery
import moe.ouom.neriplayer.data.model.unified.CatalogSearchPage
import moe.ouom.neriplayer.data.model.unified.CatalogTrack
import moe.ouom.neriplayer.data.model.unified.MusicCatalogProvider
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.SourcePlaybackMode
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Anonymous, read-only HTTPS search. No playback URL, signature, cookie or permission rewriting. */
class KuwoCatalogProvider(private val client: OkHttpClient) : MusicCatalogProvider {
    override val providerId = "kuwo"
    override val playbackMode = SourcePlaybackMode.METADATA_ONLY

    override suspend fun search(query: String, cursor: String?, limit: Int): CatalogSearchPage {
        val page = parseKuwoPageCursor(cursor)
        val url = buildKuwoCatalogSearchUrl(query, page, limit)
        return withTimeoutOrNull(6_000) {
            client.newCall(Request.Builder().url(url).header("User-Agent", "UniMusic-catalog/1.0").build())
                .awaitResponse { response ->
                    if (!response.isSuccessful) throw IOException("Kuwo catalog HTTP ${response.code}")
                    val body = response.body.byteStream().readBytesLimited(2L * 1024 * 1024).toString(Charsets.UTF_8)
                    parseKuwoCatalogSearch(body, page, limit)
                }
        } ?: throw SocketTimeoutException("Kuwo catalog timed out")
    }
}

internal fun parseKuwoPageCursor(cursor: String?): Int {
    if (cursor == null) return 0
    return cursor.toIntOrNull()?.takeIf { it >= 0 && it.toString() == cursor }
        ?: throw IllegalArgumentException("Invalid Kuwo catalog cursor")
}

internal fun buildKuwoCatalogSearchUrl(query: String, page: Int, limit: Int): HttpUrl {
    val normalized = normalizeFederatedQuery(query)
    require(normalized.isNotBlank() && normalized.length <= 256)
    require(page >= 0 && limit in 1..50)
    return "https://search.kuwo.cn/r.s".toHttpUrl().newBuilder()
        .addQueryParameter("all", normalized).addQueryParameter("ft", "music")
        .addQueryParameter("pn", page.toString()).addQueryParameter("rn", limit.toString())
        .addQueryParameter("rformat", "json").addQueryParameter("encoding", "utf8").build()
}

internal fun parseKuwoCatalogSearch(body: String, page: Int, limit: Int): CatalogSearchPage {
    require(page >= 0 && limit in 1..50)
    // JSONTokener accepts the endpoint's single-quoted object literals. Never evaluate JavaScript.
    val root = JSONObject(body)
    val rows = root.optJSONArray("abslist") ?: throw IOException("Missing Kuwo catalog result list")
    val returnedPage = root.optString("PN").toIntOrNull()
    if (returnedPage != null && returnedPage != page) throw IOException("Unexpected Kuwo catalog page")
    val total = root.optString("TOTAL").toLongOrNull()?.takeIf { it >= 0 }
    val tracks = buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("MUSICRID").removePrefix("MUSIC_")
            if (id.toLongOrNull()?.takeIf { it > 0 }?.toString() != id) continue
            val title = decodeKuwoCatalogText(row.optString("SONGNAME").ifBlank { row.optString("NAME") })
            val artist = decodeKuwoCatalogText(row.optString("ARTIST"))
            if (title.isBlank() || artist.isBlank()) continue
            val seconds = row.optString("DURATION").toLongOrNull()?.takeIf { it in 0..604_800 } ?: 0
            add(CatalogTrack(SongSourceKey("kuwo", id), UnifiedSongMetadata(title, artist,
                decodeKuwoCatalogText(row.optString("ALBUM")), seconds * 1000),
                externalPageUrl = "https://www.kuwo.cn/play_detail/$id"))
        }
    }.distinctBy { it.source }
    // Paging uses raw row count: invalid/duplicate metadata must not shift the platform cursor.
    val next = (page + 1).takeIf {
        page < Int.MAX_VALUE && rows.length() > 0 &&
            (total?.let { count -> (page.toLong() + 1) * limit < count } ?: (rows.length() >= limit))
    }?.toString()
    return CatalogSearchPage(tracks, next, total)
}

private fun decodeKuwoCatalogText(value: String): String = decodeCatalogMetadataText(value)
