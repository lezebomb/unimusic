package moe.ouom.neriplayer.core.player.policy.source

import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

enum class SourceFailure {
    NO_MATCH, AMBIGUOUS, AUTH_REQUIRED, NO_PERMISSION, EXTERNAL_PLAYER_REQUIRED,
    NETWORK, TIMEOUT, URL_EXPIRED, PREVIEW_ONLY, UNAVAILABLE
}

/** Stale context failures carry no evidence about the current account or automatic mapping. */
enum class AutomaticSourceFailureAction(val invalidateMapping: Boolean, val coolSource: Boolean) {
    NONE(false, false), INVALIDATE(true, false), COOL(false, true), INVALIDATE_AND_COOL(true, true)
}

fun automaticSourceFailureAction(reason: SourceFailure, contextCurrent: Boolean): AutomaticSourceFailureAction = when {
    !contextCurrent -> AutomaticSourceFailureAction.NONE
    reason == SourceFailure.UNAVAILABLE -> AutomaticSourceFailureAction.INVALIDATE_AND_COOL
    reason in setOf(SourceFailure.NO_MATCH, SourceFailure.NO_PERMISSION, SourceFailure.PREVIEW_ONLY) ->
        AutomaticSourceFailureAction.INVALIDATE
    reason in setOf(SourceFailure.NETWORK, SourceFailure.TIMEOUT, SourceFailure.URL_EXPIRED) ->
        AutomaticSourceFailureAction.COOL
    else -> AutomaticSourceFailureAction.NONE
}

sealed interface SourceAttempt<out T> {
    data class Ready<T>(val value: T) : SourceAttempt<T>
    data class Rejected(val reason: SourceFailure) : SourceAttempt<Nothing>
}

data class PlaybackSourceCandidate<T>(
    val id: String,
    /** Provider/session policy, separate from recording similarity; never inferred from a URL. */
    val audioAllowed: Boolean,
    val resolve: suspend () -> SourceAttempt<T>
)

data class SourceResolution<T>(val value: T?, val candidateId: String?, val failures: List<SourceFailure>)

/** Bounded in-memory failure state. Stores IDs only, never credentials or signed URLs. */
class SourceFailureCooldown(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Key(val request: String, val source: String)
    private val failures = LinkedHashMap<Key, Long>()

    @Synchronized fun reject(request: String, source: String, durationMs: Long = 60_000) {
        require(durationMs >= 0)
        val timestamp = now()
        failures.entries.removeAll { it.value <= timestamp }
        failures[Key(request, source)] = timestamp + durationMs
        while (failures.size > 256) failures.remove(failures.keys.first())
    }

    @Synchronized fun cooling(request: String, source: String): Boolean {
        val expires = failures[Key(request, source)] ?: return false
        return expires > now()
    }
}

class PlaybackSourceResolver(private val cooldown: SourceFailureCooldown = SourceFailureCooldown()) {
    suspend fun <T> resolve(
        request: String,
        candidates: Flow<PlaybackSourceCandidate<T>>,
        totalBudgetMs: Long = 10_000,
        candidateBudgetMs: Long = 3_000
    ): SourceResolution<T> {
        require(totalBudgetMs > 0 && candidateBudgetMs > 0)
        val reasons = mutableListOf<SourceFailure>()
        var selected: T? = null
        var selectedId: String? = null
        val visited = hashSetOf<String>()
        val completed = withTimeoutOrNull(totalBudgetMs) {
            candidates.firstOrNull { candidate ->
                if (!visited.add(candidate.id) || cooldown.cooling(request, candidate.id)) return@firstOrNull false
                if (!candidate.audioAllowed) {
                    reasons += SourceFailure.EXTERNAL_PLAYER_REQUIRED
                    return@firstOrNull false
                }
                val attempt = attempt(candidate, candidateBudgetMs)
                when (attempt) {
                    is SourceAttempt.Ready -> { selected = attempt.value; selectedId = candidate.id; true }
                    is SourceAttempt.Rejected -> {
                        reasons += attempt.reason
                        coolFailure(request, candidate.id, attempt.reason)
                        false
                    }
                }
            }
            true
        }
        if (completed == null) reasons += SourceFailure.TIMEOUT
        return SourceResolution(selected, selectedId, reasons.toList())
    }

    /** At most two previously validated automatic sources. Manual source order uses resolve(). */
    suspend fun <T> resolveParallel(
        request: String,
        candidates: List<PlaybackSourceCandidate<T>>,
        totalBudgetMs: Long,
        candidateBudgetMs: Long
    ): SourceResolution<T> {
        require(totalBudgetMs > 0 && candidateBudgetMs > 0 && candidates.size <= 2)
        val reasons = ConcurrentLinkedQueue<SourceFailure>()
        var completed = false
        val winner = withTimeoutOrNull(totalBudgetMs) {
            racePlaybackSources(candidates.distinctBy { it.id }.map { candidate -> suspend {
                if (cooldown.cooling(request, candidate.id)) null
                else if (!candidate.audioAllowed) {
                    reasons.add(SourceFailure.EXTERNAL_PLAYER_REQUIRED)
                    null
                } else when (val result = attempt(candidate, candidateBudgetMs)) {
                    is SourceAttempt.Ready -> SourceResolution(result.value, candidate.id, emptyList())
                    is SourceAttempt.Rejected -> {
                        reasons.add(result.reason)
                        coolFailure(request, candidate.id, result.reason)
                        null
                    }
                }
            } }).also { completed = true }
        }
        // A cancelled losing child or the caller's shared deadline is not evidence against that source.
        if (!completed) reasons.add(SourceFailure.TIMEOUT)
        return SourceResolution(winner?.value, winner?.candidateId, reasons.toList())
    }

    private suspend fun <T> attempt(candidate: PlaybackSourceCandidate<T>, budgetMs: Long): SourceAttempt<T> = try {
        withTimeoutOrNull(budgetMs) { candidate.resolve() } ?: SourceAttempt.Rejected(SourceFailure.TIMEOUT)
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        SourceAttempt.Rejected(if (cancelled is TimeoutCancellationException) SourceFailure.TIMEOUT else SourceFailure.UNAVAILABLE)
    } catch (_: java.net.SocketTimeoutException) {
        SourceAttempt.Rejected(SourceFailure.TIMEOUT)
    } catch (_: java.io.IOException) {
        SourceAttempt.Rejected(SourceFailure.NETWORK)
    } catch (_: Exception) {
        SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
    }

    private fun coolFailure(request: String, source: String, reason: SourceFailure) {
        if (reason in setOf(SourceFailure.NETWORK, SourceFailure.TIMEOUT, SourceFailure.URL_EXPIRED, SourceFailure.UNAVAILABLE)) {
            cooldown.reject(request, source)
        }
    }
}
