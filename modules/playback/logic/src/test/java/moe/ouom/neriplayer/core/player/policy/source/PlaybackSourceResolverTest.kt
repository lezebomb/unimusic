package moe.ouom.neriplayer.core.player.policy.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceResolverTest {
    @Test fun recentPrimaryHintsExpireWithoutSlidingAndCannotCrossAccountsOrSources() {
        var now = 0L
        val hints = RecentPrimaryFailureHint { now }
        val account = Any();val changed = Any()
        hints.record("song", "qqmusic", account)
        assertTrue(hints.recent("song", "qqmusic", account))
        assertFalse(hints.recent("song", "kuwo", account));assertFalse(hints.recent("other", "qqmusic", account))
        now = 9_999;assertTrue(hints.recent("song", "qqmusic", account))
        now = 10_000;assertFalse(hints.recent("song", "qqmusic", account))
        hints.record("song", "qqmusic", account)
        assertFalse(hints.recent("song", "qqmusic", changed));assertFalse(hints.recent("song", "qqmusic", account))
        hints.record("song", "qqmusic", changed);hints.clear("song", "qqmusic", account)
        assertTrue(hints.recent("song", "qqmusic", changed))
        hints.clear("song", "qqmusic", changed);assertFalse(hints.recent("song", "qqmusic", changed))
        for (i in 0..64) hints.record("song-$i", "kuwo", account)
        assertFalse(hints.recent("song-0", "kuwo", account));assertTrue(hints.recent("song-64", "kuwo", account))
    }

    @Test fun staleContextFailuresHaveNoAutomaticMappingOrCooldownSideEffects() {
        for (failure in SourceFailure.entries) {
            val action = automaticSourceFailureAction(failure, contextCurrent = false)
            assertFalse(action.invalidateMapping);assertFalse(action.coolSource)
        }
        for (failure in listOf(SourceFailure.AUTH_REQUIRED, SourceFailure.EXTERNAL_PLAYER_REQUIRED, SourceFailure.AMBIGUOUS)) {
            assertEquals(AutomaticSourceFailureAction.NONE, automaticSourceFailureAction(failure, true))
        }
    }

    @Test fun permissionAndMetadataFailuresInvalidateEvidenceWithoutCoolingANewLogin() {
        for (failure in listOf(SourceFailure.NO_PERMISSION, SourceFailure.NO_MATCH, SourceFailure.PREVIEW_ONLY)) {
            assertEquals(AutomaticSourceFailureAction.INVALIDATE, automaticSourceFailureAction(failure, true))
        }
        for (failure in listOf(SourceFailure.NETWORK, SourceFailure.TIMEOUT, SourceFailure.URL_EXPIRED)) {
            assertEquals(AutomaticSourceFailureAction.COOL, automaticSourceFailureAction(failure, true))
        }
        assertEquals(AutomaticSourceFailureAction.INVALIDATE_AND_COOL,
            automaticSourceFailureAction(SourceFailure.UNAVAILABLE, true))
    }

    @Test fun cachedParallelSourcesReturnTheFastSuccessWithoutCoolingTheCancelledLoser() = runTest {
        val cooldown = SourceFailureCooldown { testScheduler.currentTime }
        var stopped = false
        val result = PlaybackSourceResolver(cooldown).resolveParallel("song", listOf(
            PlaybackSourceCandidate("slow", true) {
                try { delay(9000);SourceAttempt.Ready("late") } finally { stopped = true }
            },
            PlaybackSourceCandidate("fast", true) { delay(40);SourceAttempt.Ready("audio") }
        ), totalBudgetMs = 1200, candidateBudgetMs = 1000)
        assertEquals("audio", result.value)
        assertEquals("fast", result.candidateId)
        assertEquals(40L, testScheduler.currentTime)
        assertTrue(stopped)
        assertFalse(cooldown.cooling("song", "slow"))
        assertTrue(result.failures.isEmpty())
    }

    @Test fun sharedParallelDeadlineAndCallerCancellationDoNotBlacklistUnfinishedSources() = runTest {
        val cooldown = SourceFailureCooldown { testScheduler.currentTime }
        val resolver = PlaybackSourceResolver(cooldown)
        val candidates = listOf("a", "b").map { id -> PlaybackSourceCandidate(id, true) {
            delay(9000);SourceAttempt.Ready("late")
        } }
        val result = resolver.resolveParallel("song", candidates, totalBudgetMs = 20, candidateBudgetMs = 1000)
        assertNull(result.value)
        assertEquals(listOf(SourceFailure.TIMEOUT), result.failures)
        assertFalse(cooldown.cooling("song", "a"))
        assertFalse(cooldown.cooling("song", "b"))
        var cancelled = false
        try { withTimeout(10) { resolver.resolveParallel("song", candidates, 1200, 1000) } }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertFalse(cooldown.cooling("song", "a"))
        assertFalse(cooldown.cooling("song", "b"))
    }

    @Test fun parallelCandidatesStillEnforceEligibilityAndCoolOnlyAnActualTransientFailure() = runTest {
        val cooldown = SourceFailureCooldown { testScheduler.currentTime }
        val resolver = PlaybackSourceResolver(cooldown)
        val result = resolver.resolveParallel("song", listOf(
            PlaybackSourceCandidate<String>("broken", true) { throw java.io.IOException("fixture") },
            PlaybackSourceCandidate<String>("external", false) { error("Ineligible source must not resolve") }
        ), 1200, 1000)
        assertNull(result.value)
        assertTrue(SourceFailure.NETWORK in result.failures)
        assertTrue(SourceFailure.EXTERNAL_PLAYER_REQUIRED in result.failures)
        assertTrue(cooldown.cooling("song", "broken"))
        assertFalse(cooldown.cooling("song", "external"))
    }

    @Test fun anAdaptersOwnTimeoutOrExceptionAdvancesToTheNextCandidate() = runTest {
        val cooldown = SourceFailureCooldown { testScheduler.currentTime }
        val result = PlaybackSourceResolver(cooldown).resolve("song", flowOf(
            PlaybackSourceCandidate<String>("inner-timeout", true) { withTimeout(10) { delay(9000);SourceAttempt.Ready("late") } },
            PlaybackSourceCandidate<String>("broken-parser", true) { throw IllegalArgumentException("fixture") },
            PlaybackSourceCandidate("good", true) { SourceAttempt.Ready("audio") }
        ))
        assertEquals("audio", result.value)
        assertEquals(listOf(SourceFailure.TIMEOUT, SourceFailure.UNAVAILABLE), result.failures)
        assertEquals(10L, testScheduler.currentTime)
        assertTrue(cooldown.cooling("song", "inner-timeout"))
    }

    @Test fun freshSourceFamiliesRaceWithoutPublishingLoserEffects() = runTest {
        val updates = mutableListOf<String>()
        var loserStopped = false
        val result = raceFreshPlaybackSources<String>(listOf(
            { effects ->
                try { delay(3_800); "slow native" }
                finally { loserStopped = true; effects.emitError { updates += "loser error" } }
            },
            { effects -> delay(80); effects.updateDuration { updates += "early duration" }; "qualified video" }
        ), isCurrent = { true }, applyWinner = { updates += it })
        assertEquals("qualified video", result)
        assertTrue(loserStopped)
        assertEquals(listOf("qualified video"), updates)
        assertEquals(80L, testScheduler.currentTime)
    }

    @Test fun freshSourceRaceRechecksContextAfterLoserCleanupAndHonorsParentDeadline() = runTest {
        var current = true
        var applied = 0
        val stale = raceFreshPlaybackSources<String>(listOf(
            { _ -> try { delay(900); "slow" } finally { current = false } },
            { _ -> delay(10); "ready before logout" }
        ), isCurrent = { current }, applyWinner = { applied++ })
        assertNull(stale)
        assertEquals(0, applied)
        var stopped = 0
        val start = testScheduler.currentTime
        val budget = SourceResolutionBudget(100) { testScheduler.currentTime }
        val timeout = kotlinx.coroutines.withContext(budget) {
            withSourceResolutionBudget {
                raceFreshPlaybackSources<String>(List(2) {
                    suspend { _: moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects ->
                        try { delay(1000); "late" } finally { stopped++ }
                    }
                }, isCurrent = { true }, applyWinner = { applied++ })
            }
        }
        assertNull(timeout)
        assertEquals(2, stopped)
        assertEquals(0, applied)
        assertEquals(100L, testScheduler.currentTime - start)
    }

    @Test fun aSelfCancelledParallelSourceCannotLeaveTheReceiverWaitingForItsMissingResult() = runTest {
        val result = racePlaybackSources<String>(listOf(
            { kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel();kotlinx.coroutines.awaitCancellation() },
            { delay(10);null }
        ))
        assertNull(result)
        assertEquals(10L, testScheduler.currentTime)
        var loserStopped = false
        assertEquals("audio", racePlaybackSources(listOf(
            { try { delay(9000);"late" } finally { loserStopped = true } },
            { delay(5);"audio" }
        )))
        assertTrue(loserStopped)
        assertEquals(15L, testScheduler.currentTime)
    }

    @Test fun parallelRaceStillPropagatesCallerCancellationAndStopsEveryChild() = runTest {
        var stopped = 0
        var cancelled = false
        try {
            withTimeout(5) { racePlaybackSources(List(2) {
                suspend { try { delay(9000);"late" } finally { stopped++ } }
            }) }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(2, stopped)
        assertEquals(5L, testScheduler.currentTime)
    }

    @Test fun firstSuccessStopsDiscoveryAndSkipsIneligibleAudio() = runTest {
        var blockedCalls = 0
        var lateCalls = 0
        val candidates = flow {
            emit(PlaybackSourceCandidate<String>("external", false) { blockedCalls++; SourceAttempt.Ready("not allowed") })
            emit(PlaybackSourceCandidate("valid", true) { SourceAttempt.Ready("audio") })
            lateCalls++
            emit(PlaybackSourceCandidate("slow-backup", true) { delay(9000); SourceAttempt.Ready("backup") })
        }
        val result = PlaybackSourceResolver().resolve("song", candidates)
        assertEquals("audio", result.value)
        assertEquals("valid", result.candidateId)
        assertEquals(0, blockedCalls)
        assertEquals(0, lateCalls)
        assertTrue(SourceFailure.EXTERNAL_PLAYER_REQUIRED in result.failures)
    }

    @Test fun transientFailureCoolsDownWithoutPoisoningOtherSongs() = runTest {
        var time = -1000L // A monotonic clock may have an arbitrary, negative origin.
        var attempts = 0
        val cooldown = SourceFailureCooldown { time }
        val resolver = PlaybackSourceResolver(cooldown)
        val candidates = flowOf(
            PlaybackSourceCandidate<String>("broken", true) { attempts++; SourceAttempt.Rejected(SourceFailure.NETWORK) },
            PlaybackSourceCandidate("good", true) { SourceAttempt.Ready("audio") }
        )
        assertEquals("audio", resolver.resolve("one", candidates).value)
        resolver.resolve("one", candidates)
        assertEquals(1, attempts)
        resolver.resolve("other-song", candidates)
        assertEquals(2, attempts)
        time += 61_000
        resolver.resolve("one", candidates)
        assertEquals(3, attempts)
    }

    @Test fun totalBudgetIncludesDiscoveryAndEachSlowCandidate() = runTest {
        val started = testScheduler.currentTime
        val candidates = flow {
            delay(500) // Metadata discovery is part of the same total budget.
            repeat(10) { index -> emit(PlaybackSourceCandidate<String>("slow-$index", true) {
                delay(5000); SourceAttempt.Ready("too late")
            }) }
        }
        val result = PlaybackSourceResolver().resolve("song", candidates, totalBudgetMs = 2500, candidateBudgetMs = 1000)
        assertNull(result.value)
        assertEquals(2500L, testScheduler.currentTime - started)
        assertTrue(SourceFailure.TIMEOUT in result.failures)
    }

    @Test fun parentCancellationPropagatesAndDoesNotBlacklistTheSource() = runTest {
        val cooldown = SourceFailureCooldown()
        var cancelled = false
        try {
            withTimeout(100) {
                PlaybackSourceResolver(cooldown).resolve("song", flowOf(PlaybackSourceCandidate<String>("slow", true) {
                    delay(9000); SourceAttempt.Ready("audio")
                }))
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertFalse(cooldown.cooling("song", "slow"))
    }
}
