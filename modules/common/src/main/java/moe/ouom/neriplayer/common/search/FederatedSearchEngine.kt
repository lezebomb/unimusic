package moe.ouom.neriplayer.common.search

import java.io.IOException
import java.net.SocketTimeoutException
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

enum class SearchSourceFailure { AUTH_REQUIRED, DISABLED, NETWORK, TIMEOUT, UNAVAILABLE }

class SearchSourceException(val reason: SearchSourceFailure) : Exception(reason.name)

data class SearchPage<T>(val items: List<T>, val nextCursor: String? = null, val fromCache: Boolean = false)

data class FederatedSearchProvider<T>(
    val id: String,
    val search: suspend (query: String, cursor: String?) -> SearchPage<T>
)

data class SourceSearchState<T>(
    val items: List<T> = emptyList(),
    // Empty string denotes the first page; null denotes a completed source.
    val nextCursor: String? = "",
    val loading: Boolean = false,
    val failure: SearchSourceFailure? = null,
    /** Last completed attempt, including permit wait; monotonic and never persisted. */
    val elapsedMillis: Long? = null,
    /** Whether the last completed page came from short-lived metadata cache, not a new network request. */
    val fromCache: Boolean = false
)

data class FederatedSearchSnapshot<T>(val query: String, val sources: Map<String, SourceSearchState<T>>) {
    val loading: Boolean get() = sources.values.any { it.loading }
    val hasMore: Boolean get() = sources.values.any { it.nextCursor != null }
    val paginationSourceIds: Set<String> get() = sources.filterValues { !it.loading && it.failure == null && it.nextCursor != null }.keys
    val retryableSourceIds: Set<String> get() = sources.filterValues { !it.loading && it.failure != null && it.nextCursor != null }.keys
    val hasMoreResults: Boolean get() = paginationSourceIds.isNotEmpty()

    /** Stable provider order, independent of network completion order. No cross-source identity guess. */
    fun interleavedItems(): List<T> = buildList {
        val batches = sources.values.map { it.items }
        repeat(batches.maxOfOrNull { it.size } ?: 0) { index ->
            batches.forEach { batch -> batch.getOrNull(index)?.let { add(it) } }
        }
    }
}

/** Discovery only: adapters must not resolve playback URLs while searching. */
class FederatedSearchEngine<T>(
    private val providers: List<FederatedSearchProvider<T>>,
    private val identity: (T) -> String,
    private val sourceBudgetMs: Long = 6_000,
    parallelism: Int = 3,
    localProviderIds: Set<String> = emptySet(),
    private val monotonicNanos: () -> Long = System::nanoTime
) {
    private val permits = Semaphore(parallelism)
    private val localProviders = localProviderIds.toSet()

    init {
        require(sourceBudgetMs > 0 && parallelism > 0)
        require(providers.map { it.id }.distinct().size == providers.size)
        require(providers.map { it.id }.containsAll(localProviders)) { "Unknown local search provider" }
    }

    fun search(query: String, previous: FederatedSearchSnapshot<T>? = null,
        sourceIds: Set<String>? = null): Flow<FederatedSearchSnapshot<T>> = channelFlow {
        val normalized = normalizeFederatedQuery(query)
        require(normalized.isNotBlank())
        require(previous == null || previous.query == normalized) { "Cannot reuse another query's cursors" }
        val selected = sourceIds?.toSet()
        require(selected == null || previous != null) { "Selective continuation requires a snapshot" }
        require(selected == null || providers.map { it.id }.containsAll(selected)) { "Unknown search source" }
        val states = providers.associateTo(linkedMapOf()) { provider ->
            val prior = previous?.sources?.get(provider.id) ?: SourceSearchState()
            val requested = (selected == null || provider.id in selected) && prior.nextCursor != null
            provider.id to prior.copy(loading = requested, failure = if (requested) null else prior.failure,
                elapsedMillis = if (requested) null else prior.elapsedMillis,
                fromCache = if (requested) false else prior.fromCache)
        }
        val mutex = Mutex()
        send(FederatedSearchSnapshot(normalized, states.toMap()))
        supervisorScope {
            providers.forEach { provider ->
                val prior = states.getValue(provider.id)
                if (!prior.loading) return@forEach
                launch {
                    val started = monotonicNanos()
                    val result = try {
                        // Queueing time is included: a saturated/slow platform cannot extend the request forever.
                        withTimeoutOrNull(sourceBudgetMs) {
                            if (provider.id in localProviders) provider.search(normalized, prior.nextCursor?.ifEmpty { null })
                            else permits.withPermit { provider.search(normalized, prior.nextCursor?.ifEmpty { null }) }
                        }?.let { page ->
                            val merged = (prior.items + page.items).distinctBy(identity)
                            // A provider repeating its cursor must not cause an infinite paging loop.
                            val cursor = page.nextCursor?.takeUnless { it == prior.nextCursor || it.isEmpty() }
                            if (cursor != null && merged.size == prior.items.size) {
                                // A changing cursor without new identities must not drive an endless auto-page loop.
                                prior.copy(loading = false, failure = SearchSourceFailure.UNAVAILABLE)
                            } else SourceSearchState(merged, cursor, fromCache = page.fromCache)
                        } ?: prior.copy(loading = false, failure = SearchSourceFailure.TIMEOUT)
                    } catch (cancelled: CancellationException) {
                        // An adapter's own timeout must finish this source; actual query cancellation still propagates.
                        currentCoroutineContext().ensureActive()
                        prior.copy(loading = false, failure = if (cancelled is TimeoutCancellationException)
                            SearchSourceFailure.TIMEOUT else SearchSourceFailure.UNAVAILABLE)
                    } catch (error: SearchSourceException) {
                        prior.copy(loading = false, failure = error.reason,
                            nextCursor = if (error.reason in setOf(SearchSourceFailure.AUTH_REQUIRED, SearchSourceFailure.DISABLED)) null else prior.nextCursor)
                    } catch (_: SocketTimeoutException) {
                        prior.copy(loading = false, failure = SearchSourceFailure.TIMEOUT)
                    } catch (_: IOException) {
                        prior.copy(loading = false, failure = SearchSourceFailure.NETWORK)
                    } catch (_: Exception) {
                        prior.copy(loading = false, failure = SearchSourceFailure.UNAVAILABLE)
                    }
                    mutex.withLock {
                        states[provider.id] = result.copy(elapsedMillis =
                            ((monotonicNanos() - started) / 1_000_000).coerceAtLeast(0L))
                        send(FederatedSearchSnapshot(normalized, states.toMap()))
                    }
                }
            }
        }
    }
}

fun normalizeFederatedQuery(query: String): String =
    Normalizer.normalize(query, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ")
