package moe.ouom.neriplayer.core.player.policy.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SourceResolutionBudgetTest {
    @Test fun primaryAndFallbackShareOneDeadlineAndDoNotAddIndependentWaits() = runTest {
        val started = testScheduler.currentTime
        val budget = SourceResolutionBudget(8_000) { testScheduler.currentTime }
        var lastStageStopped = false
        val result = withContext(budget) {
            withSourceResolutionBudget {
                assertEquals(2_000L, sourceStageBudgetMs(2_000, 8_000))
                delay(2_000) // Remembered source.
                assertEquals(2_500L, sourceStageBudgetMs(2_500, 8_000))
                delay(2_500) // Primary source.
                assertEquals(3_500L, sourceStageBudgetMs(5_000, 10_000))
                try { delay(10_000) } finally { lastStageStopped = true }
                "late audio"
            }
        }
        assertNull(result)
        assertTrue(lastStageStopped)
        assertEquals(8_000L, testScheduler.currentTime - started)
    }

    @Test fun nestedRecoveryCannotResetTheDeadlineAndStandaloneChecksKeepTheirBudget() = runTest {
        assertEquals(8_000L, sourceStageBudgetMs(2_500, 8_000))
        val budget = SourceResolutionBudget(100) { testScheduler.currentTime }
        var nestedStopped = false
        val result = withContext(budget) {
            withSourceResolutionBudget {
                delay(75)
                assertEquals(25L, sourceStageBudgetMs(2_500))
                withSourceResolutionBudget(8_000) {
                    try { delay(50) } finally { nestedStopped = true }
                    "too late"
                }
            }
        }
        assertNull(result)
        assertTrue(nestedStopped)
        assertEquals(100L, testScheduler.currentTime)
    }
}
