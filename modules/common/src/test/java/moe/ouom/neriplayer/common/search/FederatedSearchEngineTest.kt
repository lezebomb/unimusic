package moe.ouom.neriplayer.common.search

import java.io.IOException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FederatedSearchEngineTest {
    @Test fun fastResultsSurviveOtherSourcesFailingOrTimingOut() = runTest {
        val engine = FederatedSearchEngine(listOf(
            FederatedSearchProvider("fast") { _: String, _: String? -> delay(10); SearchPage(listOf("song")) },
            FederatedSearchProvider<String>("failed") { _, _ -> throw IOException("fixture") },
            FederatedSearchProvider<String>("slow") { _, _ -> delay(9000); SearchPage(emptyList()) }
        ), identity = { it }, sourceBudgetMs = 1000)
        val snapshots = engine.search("  Ｄｒｅａｍ　 song  ").toList()
        val early = snapshots.first { it.sources.getValue("fast").items.isNotEmpty() }
        assertTrue(early.loading)
        val final = snapshots.last()
        assertFalse(final.loading)
        assertEquals("Dream song", final.query)
        assertEquals(listOf("song"), final.interleavedItems())
        assertEquals(SearchSourceFailure.NETWORK, final.sources.getValue("failed").failure)
        assertEquals(SearchSourceFailure.TIMEOUT, final.sources.getValue("slow").failure)
        assertEquals(1000L, testScheduler.currentTime)
    }

    @Test fun slowLocalIndexDoesNotOccupyAnOnlineSlotOrDelayTheFastProvider() = runTest {
        var active = 0
        var maximum = 0
        val engine = FederatedSearchEngine(listOf(
            FederatedSearchProvider("local") { _: String, _: String? -> delay(9000);SearchPage(listOf("local")) },
            FederatedSearchProvider("slow") { _: String, _: String? ->
                active++;maximum = maxOf(maximum, active)
                try { delay(9000);SearchPage(listOf("slow")) } finally { active-- }
            },
            FederatedSearchProvider("fast") { _: String, _: String? ->
                active++;maximum = maxOf(maximum, active)
                try { delay(10);SearchPage(listOf("fast")) } finally { active-- }
            }
        ), identity = { it }, sourceBudgetMs = 500, parallelism = 2, localProviderIds = setOf("local"))
        val snapshots = mutableListOf<Pair<Long, FederatedSearchSnapshot<String>>>()
        engine.search("song").collect { snapshots += testScheduler.currentTime to it }
        val early = snapshots.first { "fast" in it.second.interleavedItems() }
        assertEquals(10L, early.first);assertTrue(early.second.loading)
        assertEquals(2, maximum);assertEquals(0, active)
        assertEquals(500L, testScheduler.currentTime)
        assertEquals(SearchSourceFailure.TIMEOUT, snapshots.last().second.sources.getValue("local").failure)
    }

    @Test fun pagingRetriesOnlyPendingSourcesAndDeduplicatesWithinEachSource() = runTest {
        val cursors = mutableListOf<String?>()
        var attempts = 0
        val engine = FederatedSearchEngine(listOf(
            FederatedSearchProvider("paged") { _: String, cursor: String? ->
                cursors += cursor
                if (cursor == null) SearchPage(listOf("same", "first"), "next")
                else SearchPage(listOf("same", "second"), "next") // Repeated cursor terminates.
            },
            FederatedSearchProvider("retry") { _: String, _: String? ->
                if (++attempts == 1) throw IOException("fixture")
                SearchPage(listOf("same"))
            }
        ), identity = { it })
        val first = engine.search("song").toList().last()
        val second = engine.search("song", first).toList().last()
        assertEquals(listOf(null, "next"), cursors)
        assertEquals(listOf("same", "first", "second"), second.sources.getValue("paged").items)
        assertEquals(listOf("same", "same", "first", "second"), second.interleavedItems())
        assertFalse(second.hasMore)
        engine.search("song", second).toList()
        assertEquals(2, attempts)
    }

    @Test fun concurrencyIsBoundedAndQueueTimeCountsAgainstBudget() = runTest {
        var active = 0
        var maximum = 0
        val providers = (0..3).map { index -> FederatedSearchProvider("source-$index") { _: String, _: String? ->
            active++
            maximum = maxOf(maximum, active)
            try { delay(1000); SearchPage(listOf("$index")) } finally { active-- }
        } }
        val result = FederatedSearchEngine(providers, { it }, sourceBudgetMs = 1500, parallelism = 2)
            .search("song").toList().last()
        assertEquals(2, maximum)
        assertEquals(2, result.sources.values.count { it.failure == SearchSourceFailure.TIMEOUT })
        assertEquals(1500L, testScheduler.currentTime)
        assertEquals(0, active)
    }

    @Test fun parentCancellationStopsWorkAndDisabledSourceCannotBePaged() = runTest {
        var cancelled = false
        val engine = FederatedSearchEngine(listOf(
            FederatedSearchProvider<String>("disabled") { _, _ -> throw SearchSourceException(SearchSourceFailure.DISABLED) },
            FederatedSearchProvider("slow") { _: String, _: String? ->
                try { delay(9000); SearchPage(listOf("stale")) } finally { cancelled = true }
            }
        ), identity = { it })
        val snapshots = mutableListOf<FederatedSearchSnapshot<String>>()
        val job = launch { engine.search("old").collect { snapshots += it } }
        advanceTimeBy(100)
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertNull(snapshots.last().sources.getValue("disabled").nextCursor)
        assertTrue(snapshots.none { "stale" in it.interleavedItems() })
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { engine.search("different", snapshots.last()).toList() }
        }
    }
}
