package moe.ouom.neriplayer.ui.viewmodel.tab.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.search.SearchPage
import moe.ouom.neriplayer.common.search.SearchTextMatcher
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.ExploreSearchResult
import moe.ouom.neriplayer.util.search.searchValues

/** Query-local local-song index; scanning, indexing and ranking never run on the UI thread. */
internal class ExploreLocalSearchSession(
    private val loadPlaylists: suspend () -> List<LocalPlaylist>,
    private val include: (SongItem) -> Boolean = { LocalSongSupport.isLocalSong(it) }
) {
    private val mutex = Mutex()
    private var index: SearchTextMatcher.Index<SongItem>? = null
    private var cached: Pair<String, List<SongItem>>? = null

    suspend fun page(query: String, cursor: String?): SearchPage<ExploreSearchResult> = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        mutex.withLock {
            val offset = cursor?.toIntOrNull() ?: 0
            require(offset >= 0) { "Invalid local search cursor" }
            val ranked = cached?.takeIf { it.first == query }?.second ?: run {
                val prepared = index ?: run {
                    val unique = linkedMapOf<String, SongItem>()
                    loadPlaylists().forEach { playlist -> playlist.songs.forEach { song ->
                        context.ensureActive()
                        if (include(song)) unique.putIfAbsent(song.stableKey(), song)
                    } }
                    SearchTextMatcher.index(unique.values.toList()) {
                        context.ensureActive();it.searchValues()
                    }.also { index = it }
                }
                prepared.filterAndRank(query).also { context.ensureActive();cached = query to it }
            }
            val from = offset.coerceAtMost(ranked.size)
            val to = from + (ranked.size - from).coerceAtMost(PAGE_SIZE)
            SearchPage(ranked.subList(from, to).map(ExploreSearchResult::Song), to.takeIf { it < ranked.size }?.toString())
        }
    }

    private companion object { const val PAGE_SIZE = 30 }
}
