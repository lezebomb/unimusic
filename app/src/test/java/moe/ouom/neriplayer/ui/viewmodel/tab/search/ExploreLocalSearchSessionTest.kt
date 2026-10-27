package moe.ouom.neriplayer.ui.viewmodel.tab.search

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.ExploreSearchResult
import org.junit.Assert.*
import org.junit.Test

class ExploreLocalSearchSessionTest {
    @Test fun localIndexRunsOffCallerAndPaginationReusesItsSnapshotAndRanking() = runTest {
        val caller = Thread.currentThread()
        var loaderCalls = 0
        var includeCalls = 0
        val songs = (1L..65L).map { id -> SongItem(id, "Original $id", "Artist", "Album", 0, 180_000,
            null, channelId = "netease", audioId = id.toString()) }
        val session = ExploreLocalSearchSession(loadPlaylists = {
            assertNotSame(caller, Thread.currentThread());loaderCalls++
            listOf(LocalPlaylist(1, "Fixture", (songs + songs.first()).toMutableList()))
        }, include = { assertNotSame(caller, Thread.currentThread());includeCalls++;true })
        val first = session.page("Original", null)
        val second = session.page("Original", first.nextCursor)
        val third = session.page("Original", second.nextCursor)
        val ids = (first.items + second.items + third.items).map { (it as ExploreSearchResult.Song).song.id }
        assertEquals(65, ids.size);assertEquals(65, ids.toSet().size)
        assertEquals(listOf(30, 30, 5), listOf(first.items.size, second.items.size, third.items.size))
        assertNull(third.nextCursor)
        assertEquals(1, loaderCalls);assertEquals(66, includeCalls)
        assertTrue(session.page("Not found", null).items.isEmpty())
        assertEquals(1, loaderCalls);assertEquals(66, includeCalls)
    }
}
