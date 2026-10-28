package moe.ouom.neriplayer.common.search

import java.io.IOException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FederatedSearchEngineTest {
    @Test fun firstPageCacheReusesOnlyFreshMatchingProviderMetadataAndMarksSnapshots() = runTest {
        val cache = FirstSearchPageCache<String>(ttlMillis = 100,
            monotonicNanos = { testScheduler.currentTime * 1_000_000 })
        var calls = 0
        suspend fun page(provider: String, query: String, cursor: String? = null) =
            cache.load(provider, query, cursor) { calls++;SearchPage(listOf("song"), "next") }
        assertFalse(page("one", "  Ｄｒｅａｍ  song ").fromCache)
        val engine = FederatedSearchEngine(listOf(FederatedSearchProvider("one") { query: String, cursor: String? ->
            page("one", query, cursor)
        }), identity = { it })
        val hit = engine.search("Dream song").toList().last().sources.getValue("one")
        assertTrue(hit.fromCache);assertEquals("next", hit.nextCursor);assertEquals(1, calls)
        assertFalse(page("two", "Dream song").fromCache)
        assertFalse(page("one", "Dream song", "next").fromCache)
        advanceTimeBy(99);assertTrue(page("one", "Dream song").fromCache)
        advanceTimeBy(1);assertFalse(page("one", "Dream song").fromCache)
        assertEquals(4, calls)
    }

    @Test fun firstPageCacheCannotBeRepopulatedByInvalidatedOrCancelledLoads() = runTest {
        val cache = FirstSearchPageCache<String>()
        var calls = 0
        suspend fun load(wait: Long) = cache.load("one", "song", null) {
            calls++;delay(wait);SearchPage(listOf("song"))
        }
        val old = launch { load(20) }
        advanceTimeBy(1);cache.invalidate();old.join()
        assertFalse(load(0).fromCache);assertEquals(2, calls)
        cache.invalidate()
        val cancelled = launch { load(20) }
        advanceTimeBy(1);cancelled.cancelAndJoin()
        assertFalse(load(0).fromCache);assertEquals(4, calls)
        assertTrue(load(0).fromCache)
    }

    @Test fun firstPageCacheBoundsCopiesAndDoesNotRetainEmptyFailedOrOversizedPages() = runTest {
        val cache = FirstSearchPageCache<String>(maxEntries = 2, maxItems = 2)
        val mutable = mutableListOf("original")
        suspend fun fresh(query: String) = cache.load("one", query, null) { SearchPage(listOf(query)) }
        cache.load("one", "a", null) { SearchPage(mutable) }
        mutable += "outside"
        val hit = fresh("a");assertEquals(listOf("original"), hit.items)
        (hit.items as? MutableList<String>)?.let { runCatching { it.clear() } }
        assertEquals(listOf("original"), fresh("a").items)
        fresh("b");fresh("a");fresh("c") // Reading a keeps it ahead of b in the LRU.
        assertFalse(fresh("b").fromCache)
        cache.load("one", "empty", null) { SearchPage(emptyList()) }
        assertFalse(fresh("empty").fromCache)
        try { cache.load("one", "failed", null) { throw IOException("fixture") };fail("Expected failure") }
        catch (_: IOException) { }
        assertFalse(fresh("failed").fromCache)
        cache.load("one", "large", null) { SearchPage(listOf("1", "2", "3")) }
        assertFalse(fresh("large").fromCache)
        val longQuery = "x".repeat(257)
        assertFalse(fresh(longQuery).fromCache);assertFalse(fresh(longQuery).fromCache)
    }

    @Test fun monotonicAttemptTimesIncludeQueueingAndSelectiveRetryKeepsUnrequestedMeasurements() = runTest {
        var attempts = 0
        val engine = FederatedSearchEngine(listOf(
            FederatedSearchProvider("healthy") { _: String, _: String? -> delay(20);SearchPage(listOf("ready")) },
            FederatedSearchProvider("socket") { _: String, _: String? ->
                if (++attempts == 1) { delay(5);throw java.net.SocketTimeoutException("fixture") }
                delay(4);SearchPage(listOf("recovered"))
            }
        ), identity = { it }, sourceBudgetMs = 100, parallelism = 1,
            monotonicNanos = { testScheduler.currentTime * 1_000_000 })
        val initial = engine.search("song").toList()
        assertTrue(initial.first().sources.values.all { it.elapsedMillis == null })
        val finished = initial.last()
        assertEquals(20L, finished.sources.getValue("healthy").elapsedMillis)
        assertEquals(25L, finished.sources.getValue("socket").elapsedMillis)
        assertEquals(SearchSourceFailure.TIMEOUT, finished.sources.getValue("socket").failure)
        val retry = engine.search("song", finished, finished.retryableSourceIds).toList()
        assertNull(retry.first().sources.getValue("socket").elapsedMillis)
        assertEquals(20L, retry.last().sources.getValue("healthy").elapsedMillis)
        assertEquals(4L, retry.last().sources.getValue("socket").elapsedMillis)
        assertEquals(listOf("ready", "recovered"), retry.last().interleavedItems())
    }

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

    @Test fun healthyPaginationDoesNotRetryFailedSourcesAndExplicitRetryKeepsHealthyCursors() = runTest {
        var healthyCalls = 0
        var failedCalls = 0
        val engine = FederatedSearchEngine(listOf(
            FederatedSearchProvider("healthy") { _: String, cursor: String? ->
                healthyCalls++
                if (cursor == null) SearchPage(listOf("a"), "page2") else SearchPage(listOf("b"))
            },
            FederatedSearchProvider("flaky") { _: String, _: String? ->
                if (++failedCalls == 1) throw IOException("fixture")
                SearchPage(listOf("recovered"))
            }
        ), identity = { it })
        val initial = engine.search("song").toList().last()
        assertEquals(setOf("healthy"), initial.paginationSourceIds)
        assertEquals(setOf("flaky"), initial.retryableSourceIds)
        val page = engine.search("song", initial, initial.paginationSourceIds).toList().last()
        assertEquals(2, healthyCalls);assertEquals(1, failedCalls)
        assertFalse(page.hasMoreResults);assertEquals(listOf("a", "b"), page.sources.getValue("healthy").items)
        val retried = engine.search("song", page, page.retryableSourceIds).toList().last()
        assertEquals(2, healthyCalls);assertEquals(2, failedCalls)
        assertEquals(listOf("a", "b"), retried.sources.getValue("healthy").items)
        assertTrue(retried.retryableSourceIds.isEmpty());assertFalse(retried.hasMoreResults)
    }

    @Test fun changingCursorWithNoNewIdentityStopsAutoPagingButRemainsExplicitlyRetryable() = runTest {
        var calls = 0
        val engine = FederatedSearchEngine(listOf(FederatedSearchProvider("repeating") { _: String, _: String? ->
            calls++;SearchPage(listOf("same"), "cursor$calls")
        }), identity = { it })
        val first = engine.search("song").toList().last()
        val stalled = engine.search("song", first, first.paginationSourceIds).toList().last()
        assertEquals(2, calls)
        assertFalse(stalled.hasMoreResults)
        assertEquals(setOf("repeating"), stalled.retryableSourceIds)
        assertEquals(listOf("same"), stalled.interleavedItems())
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

    @Test fun anAdaptersOwnTimeoutFinishesItsStateWithoutCancellingHealthyResults() = runTest {
        val engine = FederatedSearchEngine(listOf(
            FederatedSearchProvider<String>("inner-timeout") { _, _ -> withTimeout(10) { delay(9000);SearchPage(emptyList()) } },
            FederatedSearchProvider("fast") { _: String, _: String? -> SearchPage(listOf("ready")) }
        ), identity = { it }, sourceBudgetMs = 100)
        val result = engine.search("song").toList().last()
        assertFalse(result.loading)
        assertEquals(SearchSourceFailure.TIMEOUT, result.sources.getValue("inner-timeout").failure)
        assertEquals(listOf("ready"), result.interleavedItems())
        assertEquals(10L, testScheduler.currentTime)
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
