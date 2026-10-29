package moe.ouom.neriplayer.ui.viewmodel.tab.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.search.FirstSearchPageCache

/** Optional metadata starts eagerly and has its own budget, independent of the primary song request. */
internal fun <T> CoroutineScope.launchOptionalSearchLookup(
    budgetMillis: Long,
    stillCurrent: () -> Boolean,
    load: suspend () -> T
): Deferred<T?> {
    require(budgetMillis > 0)
    return async {
        try {
            if (!stillCurrent()) return@async null
            val result = withTimeoutOrNull(budgetMillis) { load() }
            if (stillCurrent()) result else null
        } catch (cancelled: CancellationException) {
            // A provider may cancel itself; cancellation of this lookup/its parent must still propagate.
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) {
            null
        }
    }
}

/** Small public artist indexes may outlive song pages; owner invalidates both on account/source changes. */
internal fun <T> createArtistIntentPageCache(monotonicNanos: () -> Long = System::nanoTime): FirstSearchPageCache<T> =
    FirstSearchPageCache(ttlMillis = 120_000, maxEntries = 8, maxItems = 4, monotonicNanos = monotonicNanos)
