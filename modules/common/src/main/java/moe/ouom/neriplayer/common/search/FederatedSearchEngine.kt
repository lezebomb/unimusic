package moe.ouom.neriplayer.common.search

import java.io.IOException
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
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

data class SearchPage<T>(val items: List<T>, val nextCursor: String? = null)

data class FederatedSearchProvider<T>(
    val id: String,
    val search: suspend (query: String, cursor: String?) -> SearchPage<T>
)

data class SourceSearchState<T>(
    val items: List<T> = emptyList(),
    // Empty string denotes the first page; null denotes a completed source.
    val nextCursor: String? = "",
    val loading: Boolean = false,
    val failure: SearchSourceFailure? = null
)

data class FederatedSearchSnapshot<T>(val query: String, val sources: Map<String, SourceSearchState<T>>) {
    val loading: Boolean get() = sources.values.any { it.loading }
    val hasMore: Boolean get() = sources.values.any { it.nextCursor != null }

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
    parallelism: Int = 3
) {
    private val permits = Semaphore(parallelism)

    init {
        require(sourceBudgetMs > 0 && parallelism > 0)
        require(providers.map { it.id }.distinct().size == providers.size)
    }

    fun search(query: String, previous: FederatedSearchSnapshot<T>? = null): Flow<FederatedSearchSnapshot<T>> = channelFlow {
        val normalized = normalizeFederatedQuery(query)
        require(normalized.isNotBlank())
        require(previous == null || previous.query == normalized) { "Cannot reuse another query's cursors" }
        val states = providers.associateTo(linkedMapOf()) { provider ->
            val prior = previous?.sources?.get(provider.id) ?: SourceSearchState()
            provider.id to prior.copy(loading = prior.nextCursor != null,
                failure = if (prior.nextCursor != null) null else prior.failure)
        }
        val mutex = Mutex()
        send(FederatedSearchSnapshot(normalized, states.toMap()))
        supervisorScope {
            providers.forEach { provider ->
                val prior = states.getValue(provider.id)
                if (!prior.loading) return@forEach
                launch {
                    val result = try {
                        // Queueing time is included: a saturated/slow platform cannot extend the request forever.
                        withTimeoutOrNull(sourceBudgetMs) {
                            permits.withPermit { provider.search(normalized, prior.nextCursor?.ifEmpty { null }) }
                        }?.let { page ->
                            val merged = (prior.items + page.items).distinctBy(identity)
                            // A provider repeating its cursor must not cause an infinite paging loop.
                            val cursor = page.nextCursor?.takeUnless { it == prior.nextCursor || it.isEmpty() }
                            SourceSearchState(merged, cursor)
                        } ?: prior.copy(loading = false, failure = SearchSourceFailure.TIMEOUT)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: SearchSourceException) {
                        prior.copy(loading = false, failure = error.reason,
                            nextCursor = if (error.reason in setOf(SearchSourceFailure.AUTH_REQUIRED, SearchSourceFailure.DISABLED)) null else prior.nextCursor)
                    } catch (_: IOException) {
                        prior.copy(loading = false, failure = SearchSourceFailure.NETWORK)
                    } catch (_: Exception) {
                        prior.copy(loading = false, failure = SearchSourceFailure.UNAVAILABLE)
                    }
                    mutex.withLock {
                        states[provider.id] = result
                        send(FederatedSearchSnapshot(normalized, states.toMap()))
                    }
                }
            }
        }
    }
}

fun normalizeFederatedQuery(query: String): String =
    Normalizer.normalize(query, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ")
