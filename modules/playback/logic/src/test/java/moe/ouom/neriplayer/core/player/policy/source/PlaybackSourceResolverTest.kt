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
