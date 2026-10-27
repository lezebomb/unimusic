package moe.ouom.neriplayer.ui.screen.tab.library

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.playlist.artist.buildLocalArtistSummaries
import moe.ouom.neriplayer.data.model.playlist.LocalArtistSummary
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist

internal data class LibraryLocalSearchResult(
    val source: List<LocalPlaylist>,
    val query: String,
    val artistMode: Boolean,
    val sort: LocalArtistSortMode,
    val playlistIds: Set<Long>?, // null means every playlist, without scanning its songs.
    val artists: List<LocalArtistSummary> = emptyList(),
    val loading: Boolean = false
) {
    fun belongsTo(input: List<LocalPlaylist>, query: String, artistMode: Boolean, sort: LocalArtistSortMode) =
        source === input && this.query == query && this.artistMode == artistMode && this.sort == sort
}

/** One artist index per published library snapshot; matching never blocks the Compose thread. */
internal class LibraryLocalSearchEngine(
    private val playlistMatches: (LocalPlaylist, String, () -> Unit) -> Boolean,
    private val buildArtists: (List<LocalPlaylist>, () -> Unit) -> List<LocalArtistSummary>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val artistMutex = Mutex()
    private var artistSource: List<LocalPlaylist>? = null
    private var artistIndex: List<LocalArtistSummary> = emptyList()

    suspend fun search(
        playlists: List<LocalPlaylist>, query: String, artistMode: Boolean, sort: LocalArtistSortMode
    ): LibraryLocalSearchResult = withContext(dispatcher) {
        val coroutineContext = currentCoroutineContext()
        val checkActive = { coroutineContext.ensureActive() }
        if (!artistMode) {
            val matches: Set<Long>? = if (query.isBlank()) null else buildSet<Long> {
                playlists.forEach { playlist ->
                    checkActive()
                    if (playlistMatches(playlist, query, checkActive)) add(playlist.id)
                }
            }
            LibraryLocalSearchResult(playlists, query, false, sort, matches)
        } else {
            val index = artistMutex.withLock {
                checkActive()
                if (artistSource !== playlists) {
                    val fresh = buildArtists(playlists, checkActive)
                    artistIndex = fresh
                    artistSource = playlists
                }
                artistIndex
            }
            val matches = if (query.isBlank()) index else index.filter { artist ->
                checkActive()
                artist.matchesLocalArtistSearch(query, checkActive)
            }
            checkActive()
            LibraryLocalSearchResult(playlists, query, true, sort, emptySet(), sortLocalArtists(matches, sort))
        }
    }
}

/** Compose keys must track the published snapshot by identity, without deep song-list comparisons. */
private class LibrarySearchSource(val playlists: List<LocalPlaylist>) {
    override fun equals(other: Any?) = other is LibrarySearchSource && playlists === other.playlists
    override fun hashCode() = System.identityHashCode(playlists)
}

@Composable
internal fun rememberLibraryLocalSearch(
    playlists: List<LocalPlaylist>, query: String, artistMode: Boolean, sort: LocalArtistSortMode, context: Context
): LibraryLocalSearchResult {
    val normalizedQuery = query.trim()
    if (!artistMode && normalizedQuery.isEmpty()) {
        return LibraryLocalSearchResult(playlists, normalizedQuery, false, sort, null)
    }
    val engine = remember(context) {
        LibraryLocalSearchEngine(
            playlistMatches = { playlist, text, active -> playlist.matchesLocalPlaylistSearch(text, context, active) },
            buildArtists = { source, active -> buildLocalArtistSummaries(source, context, active) }
        )
    }
    val result by produceState<LibraryLocalSearchResult?>(
        null, LibrarySearchSource(playlists), normalizedQuery, artistMode, sort, context
    ) {
        if (normalizedQuery.isNotEmpty()) delay(120L)
        value = engine.search(playlists, normalizedQuery, artistMode, sort)
    }
    return result?.takeIf { it.belongsTo(playlists, normalizedQuery, artistMode, sort) }
        ?: LibraryLocalSearchResult(playlists, normalizedQuery, artistMode, sort, emptySet(), loading = true)
}
