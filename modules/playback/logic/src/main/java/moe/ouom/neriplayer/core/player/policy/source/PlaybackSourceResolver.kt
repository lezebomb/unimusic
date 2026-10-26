package moe.ouom.neriplayer.core.player.policy.source

import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

enum class SourceFailure {
    NO_MATCH, AMBIGUOUS, AUTH_REQUIRED, NO_PERMISSION, EXTERNAL_PLAYER_REQUIRED,
    NETWORK, TIMEOUT, URL_EXPIRED, PREVIEW_ONLY, UNAVAILABLE
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
                val attempt = try {
                    withTimeoutOrNull(candidateBudgetMs) { candidate.resolve() }
                        ?: SourceAttempt.Rejected(SourceFailure.TIMEOUT)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: java.io.IOException) {
                    SourceAttempt.Rejected(SourceFailure.NETWORK)
                }
                when (attempt) {
                    is SourceAttempt.Ready -> { selected = attempt.value; selectedId = candidate.id; true }
                    is SourceAttempt.Rejected -> {
                        reasons += attempt.reason
                        if (attempt.reason in setOf(SourceFailure.NETWORK, SourceFailure.TIMEOUT, SourceFailure.URL_EXPIRED, SourceFailure.UNAVAILABLE)) {
                            cooldown.reject(request, candidate.id)
                        }
                        false
                    }
                }
            }
            true
        }
        if (completed == null) reasons += SourceFailure.TIMEOUT
        return SourceResolution(selected, selectedId, reasons.toList())
    }
}
