package moe.ouom.neriplayer.core.player.policy.source

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.unified.*
import org.junit.Assert.*
import org.junit.Test

class SourceAvailabilityCheckerTest {
    private val checker = SourceAvailabilityChecker { 123L }
    private val expected = RecordingMetadata("晴天", "周杰伦", 269_000)
    private val candidate = RecordingSourceCandidate(SongSourceKey("bilibili", "10", "11"),
        UnifiedSongMetadata("晴天", "周杰伦", "叶惠美", 269_000))

    @Test fun anAdaptersOwnTimeoutIsACompletedCheckRatherThanACancelledCaller() = runTest {
        val result = checker.check(expected, candidate, true, true, false) {
            kotlinx.coroutines.withTimeout(10) { kotlinx.coroutines.awaitCancellation() }
        }
        assertEquals(SourceCheckStatus.TIMED_OUT, result.status)
        assertEquals(10L, testScheduler.currentTime)
    }

    @Test fun capabilityIdentityAndRecordingGatesNeverResolveAnAudioUrl() = runTest {
        suspend fun refused(value: RecordingSourceCandidate, status: SourceCheckStatus,
            enabled: Boolean = true, online: Boolean = true, rejected: Boolean = false) {
            assertEquals(status, checker.check(expected, value, enabled, online, rejected) {
                error("Ineligible metadata must never resolve audio")
            }.status)
        }
        assertTrue(MusicSourceRegistry.canResolveAudio(SongSourceKey("kuwo", "42")))
        assertTrue(MusicSourceRegistry.canResolveAudio(SongSourceKey("qqmusic", "003OUlho2HcRHC")))
        refused(candidate.copy(key = SongSourceKey("other", "42")), SourceCheckStatus.UNSUPPORTED)
        refused(candidate.copy(key = SongSourceKey("bilibili", "10")), SourceCheckStatus.UNSUPPORTED)
        refused(candidate.copy(metadata = candidate.metadata.copy(title = "晴天（Ｌｉｖｅ）")), SourceCheckStatus.METADATA_MISMATCH)
        refused(candidate, SourceCheckStatus.USER_REJECTED, rejected = true)
        refused(candidate, SourceCheckStatus.OFFLINE, online = false)
        refused(candidate, SourceCheckStatus.DISABLED, enabled = false)
        assertFalse(MusicSourceRegistry.canResolveAudio(SongSourceKey("netease", "042")))
        assertFalse(MusicSourceRegistry.canResolveAudio(SongSourceKey("bilibili", "10", "11", "other-recording")))
    }

    @Test fun timeoutCancelsTheRequestAndCallerCancellationPropagates() = runTest {
        var stopped = false
        assertEquals(SourceCheckStatus.TIMED_OUT, checker.check(expected, candidate, true, true, false, budgetMs = 10) {
            try { awaitCancellation() } finally { stopped = true }
        }.status)
        assertTrue(stopped)
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            checker.check(expected, candidate, true, true, false) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun resultsAreTransientAndChangedContextWinsOverAnOldSuccess() = runTest {
        var current = true
        val changed = checker.check(expected, candidate, true, true, false, stillCurrent = { current }) {
            current = false
            SourceAttempt.Ready(Unit)
        }
        assertEquals(SourceCheckStatus.CONTEXT_CHANGED, changed.status)
        val ready = checker.check(expected, candidate, true, true, false) { SourceAttempt.Ready(Unit) }
        assertEquals(SourceCheckResult(SourceCheckStatus.ADDRESS_RESOLVED, 123), ready)
        val local = candidate.copy(key = SongSourceKey("local", "9b90cdeb-25f9-423c-a312-9368f4bc67f5"))
        assertEquals(SourceCheckStatus.LOCAL_READABLE, checker.check(expected, local, true, false, false) { SourceAttempt.Ready(Unit) }.status)
        assertEquals(SourceCheckStatus.PREVIEW_ONLY, checker.check(expected, candidate, true, true, false) {
            SourceAttempt.Rejected(SourceFailure.PREVIEW_ONLY)
        }.status)
        assertEquals(SourceCheckStatus.NETWORK, checker.check(expected, candidate, true, true, false) { throw IOException() }.status)
    }
}
