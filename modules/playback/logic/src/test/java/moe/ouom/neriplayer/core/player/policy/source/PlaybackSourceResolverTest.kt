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
