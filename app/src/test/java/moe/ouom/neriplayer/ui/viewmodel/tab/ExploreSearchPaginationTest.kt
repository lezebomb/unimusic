package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.platform.youtube.api.transport.youtubeMusicThumbnailUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent

class ExploreSearchPaginationTest {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun foregroundPlaybackCancelsProbeLockHolderWithoutRestartingOnLateMetadata() = kotlinx.coroutines.test.runTest {
        val owner = moe.ouom.neriplayer.ui.viewmodel.tab.search.ExploreNativeProbeOwner()
        val sessionLock = kotlinx.coroutines.sync.Mutex()
        assertTrue(owner.claim("kuwo"))
        val probe = launch { sessionLock.withLock { kotlinx.coroutines.awaitCancellation() } }
        owner.track(probe);runCurrent();assertTrue(sessionLock.isLocked)
        owner.yieldToPlayback()
        var playbackEntered = false
        val playback = launch { sessionLock.withLock { playbackEntered = true } }
        runCurrent();assertTrue(probe.isCancelled);assertTrue(playbackEntered);playback.join()
        assertFalse(owner.claim("qqmusic"));assertTrue(owner.isClaimed("kuwo"))
        val lateProbe = kotlinx.coroutines.Job();owner.track(lateProbe);assertTrue(lateProbe.isCancelled)
        // A new explicit query restores discovery without affecting the caller's scope.
        owner.reset();assertTrue(owner.claim("kuwo"));assertTrue(owner.claim("qqmusic"))
        assertTrue(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test fun feedLoadsCoalesceAndOldCompletionCannotClearTheReplacement() {
        val gate = ExplorePlaylistRequestGate()
        val session = Any()
        val first = requireNotNull(gate.begin("all", session))
        org.junit.Assert.assertNull(gate.begin("all", session))
        val replacement = requireNotNull(gate.begin("rock", session))
        assertFalse(gate.current(first, session))
        assertFalse(gate.finish(first))
        org.junit.Assert.assertNull(gate.begin("rock", session))
        assertTrue(gate.current(replacement, session))
        assertTrue(gate.finish(replacement))
        org.junit.Assert.assertNotNull(gate.begin("rock", session))
    }

    @Test fun feedResultsCannotCrossAnAccountChangeEvenBeforeItsObserverReloads() {
        val gate = ExplorePlaylistRequestGate()
        val oldSession = Any()
        val newSession = Any()
        val old = requireNotNull(gate.begin("all", oldSession))
        assertFalse(gate.current(old, newSession))
        val current = requireNotNull(gate.begin("all", newSession))
        assertFalse(gate.finish(old))
        assertTrue(gate.current(current, newSession))
    }

    @Test
    fun `merge keeps existing order and removes duplicate stable keys`() {
        val first = ExploreSearchResult.Notice(title = "first", message = "one")
        val second = ExploreSearchResult.Notice(title = "second", message = "two")

        assertEquals(
            listOf(first, second),
            mergeExploreSearchResults(
                existing = listOf(first),
                incoming = listOf(first, second)
            )
        )
    }

    @Test
    fun `known total count controls whether another page exists`() {
        assertTrue(
            hasMoreExploreSearchResults(
                totalCount = 61,
                loadedCount = 60,
                pageItemCount = 30,
                pageSize = 30
            )
        )
        assertFalse(
            hasMoreExploreSearchResults(
                totalCount = 60,
                loadedCount = 60,
                pageItemCount = 30,
                pageSize = 30
            )
        )
    }

    @Test
    fun `page size fallback handles APIs without a total count`() {
        assertTrue(
            hasMoreExploreSearchResults(
                totalCount = null,
                loadedCount = 30,
                pageItemCount = 30,
                pageSize = 30
            )
        )
        assertFalse(
            hasMoreExploreSearchResults(
                totalCount = null,
                loadedCount = 29,
                pageItemCount = 29,
                pageSize = 30
            )
        )
    }

    @Test
    fun `scroll threshold prefetches next page near list end`() {
        assertFalse(
            shouldLoadExploreSearchMore(
                resultCount = 30,
                lastVisibleItemIndex = 23,
                hasMore = true,
                searching = false,
                loadingMore = false
            )
        )
        assertTrue(
            shouldLoadExploreSearchMore(
                resultCount = 30,
                lastVisibleItemIndex = 24,
                hasMore = true,
                searching = false,
                loadingMore = false
            )
        )
    }

    @Test
    fun `load more failure waits for explicit retry`() {
        assertFalse(
            shouldLoadExploreSearchMore(
                resultCount = 30,
                lastVisibleItemIndex = 29,
                hasMore = true,
                searching = false,
                loadingMore = false,
                loadMoreFailed = true
            )
        )
    }

    @Test
    fun `youtube linked song has deterministic thumbnail fallback`() {
        assertEquals(
            "https://i.ytimg.com/vi/OvLE3VQ18UY/hqdefault.jpg",
            youtubeMusicThumbnailUrl("OvLE3VQ18UY")
        )
    }
}
