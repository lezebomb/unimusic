package moe.ouom.neriplayer.core.player.policy.source

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.data.model.unified.*

/** Explicit single-source checks only; search lists never resolve URLs through this coordinator. */
class SourceAvailabilityChecker(private val now: () -> Long = System::currentTimeMillis) {
    suspend fun check(
        expected: RecordingMetadata,
        candidate: RecordingSourceCandidate,
        enabled: Boolean,
        online: Boolean,
        rejected: Boolean,
        stillCurrent: suspend () -> Boolean = { true },
        budgetMs: Long = 8_000,
        resolve: suspend () -> SourceAttempt<Unit>
    ): SourceCheckResult {
        require(budgetMs > 0)
        val status = when {
            !MusicSourceRegistry.validKey(candidate.key) -> SourceCheckStatus.UNSUPPORTED
            MusicSourceRegistry.capabilities(candidate.key.provider).audio == SourceAudioSupport.METADATA_ONLY -> SourceCheckStatus.METADATA_ONLY
            !MusicSourceRegistry.hasAudioAdapter(candidate.key) -> SourceCheckStatus.UNSUPPORTED
            !MusicSourceRegistry.isEnabled(candidate.key.provider) || !enabled -> SourceCheckStatus.DISABLED
            rejected -> SourceCheckStatus.USER_REJECTED
            candidate.key.provider != "local" && !online -> SourceCheckStatus.OFFLINE
            else -> checkRecording(expected, candidate, stillCurrent, budgetMs, resolve)
        }
        return SourceCheckResult(status, now())
    }

    private suspend fun checkRecording(expected: RecordingMetadata, candidate: RecordingSourceCandidate,
        current: suspend () -> Boolean, budgetMs: Long, resolve: suspend () -> SourceAttempt<Unit>): SourceCheckStatus {
        val metadata = candidate.metadata.let { RecordingMetadata(it.title, it.artist, it.durationMs, it.version, it.album) }
        if (!RecordingMatchPolicy.assess(expected, metadata, if (candidate.key.provider == "local") 8_000 else 4_000).accepted) {
            return SourceCheckStatus.METADATA_MISMATCH
        }
        return try {
            withTimeoutOrNull(budgetMs) {
                if (!current()) return@withTimeoutOrNull SourceCheckStatus.CONTEXT_CHANGED
                val result = resolve()
                if (!current()) SourceCheckStatus.CONTEXT_CHANGED
                else when (result) {
                    is SourceAttempt.Ready -> if (candidate.key.provider == "local") SourceCheckStatus.LOCAL_READABLE else SourceCheckStatus.ADDRESS_RESOLVED
                    is SourceAttempt.Rejected -> result.reason.checkStatus()
                }
            } ?: SourceCheckStatus.TIMED_OUT
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (cancelled is TimeoutCancellationException) SourceCheckStatus.TIMED_OUT else SourceCheckStatus.UNAVAILABLE
        } catch (_: java.net.SocketTimeoutException) {
            SourceCheckStatus.TIMED_OUT
        } catch (_: IOException) {
            SourceCheckStatus.NETWORK
        } catch (_: Exception) {
            SourceCheckStatus.UNAVAILABLE
        }
    }
}

private fun SourceFailure.checkStatus(): SourceCheckStatus = when (this) {
    SourceFailure.NO_MATCH, SourceFailure.AMBIGUOUS -> SourceCheckStatus.METADATA_MISMATCH
    SourceFailure.AUTH_REQUIRED -> SourceCheckStatus.AUTH_REQUIRED
    SourceFailure.NO_PERMISSION -> SourceCheckStatus.NO_PERMISSION
    SourceFailure.EXTERNAL_PLAYER_REQUIRED -> SourceCheckStatus.EXTERNAL_PLAYER_REQUIRED
    SourceFailure.PREVIEW_ONLY -> SourceCheckStatus.PREVIEW_ONLY
    SourceFailure.NETWORK -> SourceCheckStatus.NETWORK
    SourceFailure.TIMEOUT -> SourceCheckStatus.TIMED_OUT
    SourceFailure.URL_EXPIRED, SourceFailure.UNAVAILABLE -> SourceCheckStatus.UNAVAILABLE
}
