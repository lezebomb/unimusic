package moe.ouom.neriplayer.data.recommendation

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.io.writeTextAtomically

/** Opaque undo token; it cannot undo a newer decision for the same recording. */
data class RecommendationDismissal internal constructor(internal val digest: String, internal val expiresAt: Long)

/** Device-only preferences: digests and expiry timestamps, never songs, URLs or account data. */
class LocalRecommendationFeedbackStore internal constructor(
    private val file: File,
    private val write: (File, String) -> Unit = { target, text -> target.writeTextAtomically(text) }
) {
    private val mutex = Mutex()
    private var memory: Map<String, Long>? = null
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    suspend fun activeDigests(nowMillis: Long = System.currentTimeMillis()): Set<String> = withContext(Dispatchers.IO) {
        mutex.withLock { load().filterValues { it > nowMillis }.keys.toSet() }
    }

    suspend fun dismiss(identityKey: String, nowMillis: Long = System.currentTimeMillis()): RecommendationDismissal =
        withContext(Dispatchers.IO) {
            require(identityKey.isNotBlank())
            require(nowMillis in 0 until (Long.MAX_VALUE - DISMISS_DURATION_MS))
            mutex.withLock {
                val digest = recommendationIdentityDigest(identityKey)
                // Monotonic tokens also distinguish two decisions in the same millisecond.
                val current = load()
                val expiresAt = maxOf(nowMillis + DISMISS_DURATION_MS, (current[digest] ?: 0L) + 1L)
                require(expiresAt < Long.MAX_VALUE)
                val next = current.filterValues { it > nowMillis }.toMutableMap()
                next[digest] = expiresAt
                persist(next.entries.sortedByDescending { it.value }.take(MAX_ENTRIES).associate { it.toPair() })
                RecommendationDismissal(digest, expiresAt)
            }
        }

    suspend fun undo(token: RecommendationDismissal) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = load()
            if (current[token.digest] == token.expiresAt) persist(current - token.digest)
        }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        mutex.withLock { if (load().isNotEmpty()) persist(emptyMap()) }
    }

    private fun persist(next: Map<String, Long>) {
        val text = JsonObject().apply {
            addProperty("schemaVersion", 1)
            add("dismissals", JsonArray().apply {
                next.toSortedMap().forEach { (digest, expiry) ->
                    add(JsonObject().apply { addProperty("digest", digest); addProperty("expiresAt", expiry) })
                }
            })
        }.toString()
        write(file, text) // Publish only after the atomic write succeeds.
        memory = next.toMap()
        mutableRevision.value += 1L
    }

    private fun load(): Map<String, Long> {
        memory?.let { return it }
        if (!file.exists()) return emptyMap<String, Long>().also { memory = it }
        try {
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(MAX_BYTES + 1)
                var size = 0
                while (size < buffer.size) {
                    val read = input.read(buffer, size, buffer.size - size)
                    if (read < 0) break
                    size += read
                }
                buffer.copyOf(size)
            }
            require(bytes.size <= MAX_BYTES)
            val root = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            require(root.get("schemaVersion").asString.toIntOrNull() == 1)
            val rows = root.getAsJsonArray("dismissals")
            require(rows.size() <= MAX_ENTRIES)
            val result = linkedMapOf<String, Long>()
            rows.forEach { value ->
                val row = value.asJsonObject
                val digest = row.get("digest").asString
                val expiry = requireNotNull(row.get("expiresAt").asString.toLongOrNull())
                require(DIGEST.matches(digest) && expiry in 1 until Long.MAX_VALUE && digest !in result)
                result[digest] = expiry
            }
            return result.toMap().also { memory = it }
        } catch (failure: Exception) {
            // Corrupt or future preferences stay untouched and cannot block the music library.
            throw IOException("Local recommendation preferences unavailable", failure)
        }
    }

    companion object {
        const val DISMISS_DURATION_MS = 30L * 86_400_000L
        private const val MAX_ENTRIES = 1000
        private const val MAX_BYTES = 128 * 1024
        private val DIGEST = Regex("[0-9a-f]{64}")
        @Volatile private var instance: LocalRecommendationFeedbackStore? = null

        fun getInstance(context: Context): LocalRecommendationFeedbackStore = instance ?: synchronized(this) {
            instance ?: LocalRecommendationFeedbackStore(
                File(context.applicationContext.filesDir, "unimusic-recommendations/feedback-v1.json")
            ).also { instance = it }
        }
    }
}
