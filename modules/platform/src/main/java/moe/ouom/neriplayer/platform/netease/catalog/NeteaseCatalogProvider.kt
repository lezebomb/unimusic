package moe.ouom.neriplayer.platform.netease.catalog

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.unified.*
import moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseSearchSongs
import org.json.JSONObject

/** Authenticated discovery adapter; the existing NetEase resolver still verifies full audio rights. */
class NeteaseCatalogProvider(
    private val fetchPage: suspend (query: String, limit: Int, offset: Int) -> String,
    private val sessionIdentity: () -> Any,
    private val searchAvailable: () -> Boolean
) : MusicCatalogProvider {
    override val providerId = "netease"
    override val playbackMode = SourcePlaybackMode.AUTHORIZED_AUDIO

    override suspend fun search(query: String, cursor: String?, limit: Int): CatalogSearchPage {
        require(query.isNotBlank() && query.length <= 256 && limit in 1..50)
        val offset = cursor?.toIntOrNull()?.takeIf { it > 0 && it.toString() == cursor }
            ?: if (cursor == null) 0 else throw IllegalArgumentException("Invalid NetEase cursor")
        val captured = sessionIdentity()
        fun current() = searchAvailable() && captured === sessionIdentity()
        if (!current()) throw IOException("NetEase search account unavailable")
        val raw = fetchPage(query, limit, offset)
        currentCoroutineContext().ensureActive()
        if (!current()) throw IOException("NetEase search context changed")
        val page = withContext(Dispatchers.Default) {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 200) throw IOException("NetEase search rejected")
            val result = root.optJSONObject("result") ?: throw IOException("Missing NetEase search result")
            val rows = result.optJSONArray("songs")
            val count = rows?.length() ?: 0
            if (count > limit) throw IOException("Oversized NetEase search page")
            val tracks = parseNeteaseSearchSongs(root).map { song ->
                CatalogTrack(SongSourceKey(providerId, song.id.toString()),
                    UnifiedSongMetadata(song.name, song.artist, song.album, song.durationMs), null, playbackMode)
            }.distinctBy { it.source }
            val total = result.opt("songCount")?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
            val next = offset.toLong() + limit
            val more = count > 0 && (total?.let { offset.toLong() + count < it } ?: (count >= limit))
            CatalogSearchPage(tracks, next.takeIf { more && it <= Int.MAX_VALUE }?.toString(), total)
        }
        currentCoroutineContext().ensureActive()
        if (!current()) throw IOException("NetEase search context changed")
        return page
    }
}
