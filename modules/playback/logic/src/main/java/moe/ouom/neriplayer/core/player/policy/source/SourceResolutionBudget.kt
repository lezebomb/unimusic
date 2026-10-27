package moe.ouom.neriplayer.core.player.policy.source

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Explicit YouTube playback may need the upstream 12s cipher preparation; automatic fallback stays fast. */
fun primarySourceResolutionBudgetMs(isYouTube: Boolean): Long = if (isYouTube) 20_000 else 8_000

/** One deadline across preferred, primary and alternate stages; nested stages cannot reset it. */
class SourceResolutionBudget(
    private val durationMs: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SourceResolutionBudget>
    private val started = now()
    init { require(durationMs > 0) }
    fun remainingMs(): Long = (durationMs - (now() - started).coerceAtLeast(0)).coerceAtLeast(0)
}

suspend fun sourceStageBudgetMs(sharedLimitMs: Long, standaloneLimitMs: Long = sharedLimitMs): Long {
    require(sharedLimitMs > 0 && standaloneLimitMs > 0)
    return currentCoroutineContext()[SourceResolutionBudget]?.remainingMs()?.coerceAtMost(sharedLimitMs)
        ?: standaloneLimitMs
}

suspend fun <T> withSourceResolutionBudget(durationMs: Long = 8_000, block: suspend () -> T): T? {
    require(durationMs > 0)
    val inherited = currentCoroutineContext()[SourceResolutionBudget]
    val budget = inherited ?: SourceResolutionBudget(durationMs)
    return withContext(budget) {
        withTimeoutOrNull(budget.remainingMs().coerceAtMost(durationMs)) { block() }
    }
}
