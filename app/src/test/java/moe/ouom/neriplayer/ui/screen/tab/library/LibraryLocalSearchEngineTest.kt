package moe.ouom.neriplayer.ui.screen.tab.library

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playlist.LocalArtistSummary
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class LibraryLocalSearchEngineTest {
    @Test fun `playlist search preserves matching order and never builds unused artists`() = runTest {
        val context = mock(Context::class.java)
        val source = listOf(LocalPlaylist(3L, "Road trip"), LocalPlaylist(2L, "Road songs"), LocalPlaylist(1L, "Night"))
        val engine = LibraryLocalSearchEngine(
            { item, query, active -> item.matchesLocalPlaylistSearch(query, context, active) },
            { _, _ -> error("Playlist search must not build artists") }, StandardTestDispatcher(testScheduler)
        )
        val result = engine.search(source, "road", false, LocalArtistSortMode.NAME)
        assertEquals(listOf(3L, 2L), source.filter { it.id in result.playlistIds.orEmpty() }.map { it.id })
        assertNull(engine.search(source, "", false, LocalArtistSortMode.NAME).playlistIds)
    }

    @Test fun `artist index is reused for queries and invalidated for a new library snapshot`() = runTest {
        val source = listOf(LocalPlaylist(1L, "List"))
        var builds = 0
        val engine = LibraryLocalSearchEngine(
            { _, _, _ -> error("Artist search must not scan playlists again") },
            { _, active -> active(); builds++; listOf(LocalArtistSummary("Beta", emptyList()), LocalArtistSummary("Alpha", emptyList())) },
            StandardTestDispatcher(testScheduler)
        )
        assertEquals(listOf("Alpha"), engine.search(source, "alph", true, LocalArtistSortMode.NAME).artists.map { it.name })
        val all = engine.search(source, "", true, LocalArtistSortMode.NAME)
        assertEquals(listOf("Alpha", "Beta"), all.artists.map { it.name })
        assertEquals(1, builds)
        val replacement = source.toList()
        assertFalse(all.belongsTo(replacement, "", true, LocalArtistSortMode.NAME))
        engine.search(replacement, "", true, LocalArtistSortMode.NAME)
        assertEquals(2, builds)
    }

    @Test fun `cancelled scans stop before publishing an obsolete result`() = runTest {
        lateinit var pending: Job
        var scanned = 0
        var published = false
        val engine = LibraryLocalSearchEngine(
            { _, _, active -> scanned++; pending.cancel(); active(); true },
            { _, _ -> emptyList() }, StandardTestDispatcher(testScheduler)
        )
        pending = launch {
            engine.search((1L..100L).map { LocalPlaylist(it, "List") }, "query", false, LocalArtistSortMode.NAME)
            published = true
        }
        pending.join()
        assertEquals(1, scanned)
        assertFalse(published)
    }
}
