package moe.ouom.neriplayer.common.search

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** ViewModel-scoped metadata only. Never holds failures, audio URLs, credentials or in-flight jobs. */
class FirstSearchPageCache<T>(
    ttlMillis: Long = 10_000,
    private val maxEntries: Int = 12,
    private val maxItems: Int = 50,
    private val monotonicNanos: () -> Long = System::nanoTime
) {
    private data class Key(val provider: String, val query: String)
    private data class Entry<T>(val page: SearchPage<T>, val createdNanos: Long)
    private val lock = Any()
    private val ttlNanos = ttlMillis * 1_000_000
    private val entries = LinkedHashMap<Key, Entry<T>>(16, 0.75f, true)
    private var epoch = Any()

    init {
        require(ttlMillis in 1..Long.MAX_VALUE / 1_000_000 && maxEntries > 0 && maxItems > 0)
    }

    fun invalidate() = synchronized(lock) {
        entries.clear()
        epoch = Any()
    }

    suspend fun load(provider: String, query: String, cursor: String?, fetch: suspend () -> SearchPage<T>): SearchPage<T> {
        currentCoroutineContext().ensureActive()
        val normalized = normalizeFederatedQuery(query)
        // Paging/retry cursors must always reach the provider; empty results remain immediately retryable.
        if (cursor != null || normalized.isBlank() || normalized.length > 256) return fetch()
        val key = Key(provider, normalized)
        val captured = synchronized(lock) {
            val now = monotonicNanos()
            entries.entries.removeAll { now - it.value.createdNanos !in 0 until ttlNanos }
            entries[key]?.let { return it.page.copy(items = it.page.items.toList(), fromCache = true) }
            epoch
        }
        val loaded = fetch()
        currentCoroutineContext().ensureActive()
        if (loaded.items.isNotEmpty() && loaded.items.size <= maxItems && !loaded.fromCache) {
            synchronized(lock) {
                // A late response from an old account/context cannot repopulate a cleared cache.
                if (epoch === captured) {
                    entries[key] = Entry(loaded.copy(items = loaded.items.toList()), monotonicNanos())
                    while (entries.size > maxEntries) entries.remove(entries.keys.first())
                }
            }
        }
        return loaded
    }
}
