package moe.ouom.neriplayer.data.recommendation

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.io.writeTextAtomically
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackEvent
import moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackKind

/** Decayed counts for exact device-local identities. Missing evidence stays unknown. */
data class LocalPlaybackSignal(val skips: Double = 0.0, val completions: Double = 0.0, val repeats: Double = 0.0)
data class LocalPlaybackSignals(val byDigest: Map<String, LocalPlaybackSignal>, val available: Boolean, val enabled: Boolean = true)

/** Bounded, device-only evidence. Best-effort enqueue never blocks playback or authoritative statistics. */
class LocalPlaybackFeedbackStore internal constructor(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val write: (File, String) -> Unit = { target, text -> target.writeTextAtomically(text) },
    private val writerScope: CoroutineScope? = null
) {
    private data class Row(val digest: String, val event: PlaybackFeedbackEvent)
    private data class State(val clearedAt: Long = 0L, val rows: List<Row> = emptyList(), val enabled: Boolean = true)
    private sealed interface Message {
        data class Record(val digest: String, val event: PlaybackFeedbackEvent, val clearedAt: Long) : Message
        data class Flush(val done: CompletableDeferred<Unit>) : Message
    }
    private val mutex = Mutex()
    private var memory: State? = null
    @Volatile private var captureEpoch = 0L
    @Volatile private var failed = false
    private val pending = AtomicInteger()
    private val queuedDigests = ConcurrentHashMap<String, AtomicInteger>()
    private val forgottenAt = mutableMapOf<String, Long>()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private val queue by lazy {
        Channel<Message>(64).also { channel ->
            (writerScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)).launch {
                for (message in channel) when (message) {
                    is Message.Record -> try {
                        recordDigest(message.digest, message.event, message.clearedAt)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        unavailable()
                    } finally {
                        pending.decrementAndGet()
                        queuedDigests.computeIfPresent(message.digest) { _, count -> if (count.decrementAndGet() == 0) null else count }
                    }
                    is Message.Flush -> message.done.complete(Unit)
                }
            }
        }
    }

    fun enqueue(key: String, events: List<PlaybackFeedbackEvent>, clearedAt: Long) {
        val digest = recommendationIdentityDigest(key)
        events.forEach { event ->
            pending.incrementAndGet()
            queuedDigests.compute(digest) { _, count -> (count ?: AtomicInteger()).also { it.incrementAndGet() } }
            if (!queue.trySend(Message.Record(digest, event, clearedAt)).isSuccess) {
                pending.decrementAndGet()
                queuedDigests.computeIfPresent(digest) { _, count -> if (count.decrementAndGet() == 0) null else count }
                unavailable()
            }
        }
    }

    fun hasPendingWrites() = pending.get() > 0

    suspend fun flush() {
        if (!hasPendingWrites()) return
        withTimeoutOrNull(1_500L) {
            val done = CompletableDeferred<Unit>()
            queue.send(Message.Flush(done))
            done.await()
        }
    }

    internal suspend fun record(key: String, event: PlaybackFeedbackEvent, observedClearedAt: Long = 0L) {
        require(key.isNotBlank())
        recordDigest(recommendationIdentityDigest(key), event, observedClearedAt)
    }

    private suspend fun recordDigest(digest: String, event: PlaybackFeedbackEvent, observedClearedAt: Long) = withContext(Dispatchers.IO) {
        validate(event)
        mutex.withLock {
            val old = load()
            if (!old.enabled) return@withLock
            val epoch = maxOf(old.clearedAt, observedClearedAt.coerceAtLeast(0L))
            forgottenAt.keys.retainAll(queuedDigests.keys)
            val cutoff = maxOf(epoch, now() - RETENTION_MS)
            val eventCutoff = maxOf(cutoff, forgottenAt[digest] ?: 0L)
            val retained = old.rows.filter { it.event.occurredAt > cutoff }
            val next = if (event.occurredAt <= eventCutoff || retained.any { it.event.eventId == event.eventId }) retained
                else (retained + Row(digest, event)).sortedByDescending { it.event.occurredAt }.take(MAX_ROWS)
            publish(State(epoch, next, old.enabled))
        }
    }

    suspend fun signals(nowMillis: Long, clearedAt: Long): LocalPlaybackSignals = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = load()
            val epoch = maxOf(old.clearedAt, clearedAt.coerceAtLeast(0L))
            val rows = old.rows.filter { it.event.occurredAt > maxOf(epoch, nowMillis - RETENTION_MS) }
            publish(State(epoch, rows, old.enabled))
            val result = mutableMapOf<String, LocalPlaybackSignal>()
            rows.filter { old.enabled }.forEach { row ->
                val age = (nowMillis - row.event.occurredAt).coerceAtLeast(0L) / 86_400_000.0
                if (age > 14.0) return@forEach
                val weight = 1.0 / (1.0 + age / 3.0)
                val current = result[row.digest] ?: LocalPlaybackSignal()
                result[row.digest] = when (row.event.kind) {
                    PlaybackFeedbackKind.SKIP -> current.copy(skips = current.skips + weight)
                    PlaybackFeedbackKind.COMPLETE -> current.copy(completions = current.completions + weight)
                    PlaybackFeedbackKind.REPEAT -> current.copy(repeats = current.repeats + weight)
                }
            }
            LocalPlaybackSignals(result, !failed, old.enabled)
        }
    }

    suspend fun clearBefore(epoch: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = load()
            val cutoff = maxOf(old.clearedAt, epoch)
            publish(State(cutoff, old.rows.filter { it.event.occurredAt > cutoff }, old.enabled))
        }
    }

    /** Only recommendation evidence is affected; history and explicit dismissals are separate. */
    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = load()
            if (old.enabled == enabled) return@withLock
            val epoch = maxOf(old.clearedAt, now())
            publish(State(epoch, emptyList(), enabled))
            captureEpoch = epoch
        }
    }

    suspend fun clearSignals() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = load()
            val epoch = maxOf(old.clearedAt, now())
            publish(State(epoch, emptyList(), old.enabled))
            captureEpoch = epoch
        }
    }

    suspend fun forget(keys: Set<String>) {
        flush()
        val digests = keys.mapTo(hashSetOf(), ::recommendationIdentityDigest)
        val removedAt = now()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val old = load()
                forgottenAt.keys.retainAll(queuedDigests.keys)
                digests.filter { queuedDigests.containsKey(it) }.forEach { forgottenAt[it] = removedAt }
                publish(old.copy(rows = old.rows.filterNot { it.digest in digests && it.event.occurredAt <= removedAt }))
            }
        }
    }

    private fun publish(next: State) {
        val old = memory
        if (old == next) return
        val text = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("clearedAt", next.clearedAt)
            addProperty("enabled", next.enabled)
            add("events", JsonArray().apply {
                next.rows.forEach { row -> add(JsonObject().apply {
                    addProperty("digest", row.digest)
                    addProperty("id", row.event.eventId)
                    addProperty("kind", row.event.kind.name)
                    addProperty("at", row.event.occurredAt)
                    addProperty("heardMs", row.event.heardMs)
                    addProperty("durationMs", row.event.durationMs)
                }) }
            })
        }.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        write(file, text)
        memory = next
        failed = false
        mutableRevision.update { it + 1L }
    }

    internal fun unavailable() {
        if (!failed) NPLogger.w("RecommendationFeedback", "Local playback feedback unavailable")
        failed = true
        mutableRevision.update { it + 1L }
    }

    private fun load(): State {
        memory?.let { return it }
        if (!file.exists()) return State().also { memory = it }
        try {
            val bytes = file.inputStream().use { input ->
                val data = ByteArray(MAX_BYTES + 1)
                var size = 0
                while (size < data.size) {
                    val count = input.read(data, size, data.size - size)
                    if (count < 0) break
                    size += count
                }
                data.copyOf(size)
            }
            require(bytes.size <= MAX_BYTES)
            val root = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            require(root.get("schemaVersion").asString.toIntOrNull() == 1)
            val epoch = requireNotNull(root.get("clearedAt").asString.toLongOrNull()).also { require(it >= 0L) }
            val rows = root.getAsJsonArray("events")
            require(rows.size() <= MAX_ROWS)
            val parsed = rows.map { value ->
                val row = value.asJsonObject
                val digest = row.get("digest").asString.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                val event = PlaybackFeedbackEvent(row.get("id").asString, PlaybackFeedbackKind.valueOf(row.get("kind").asString),
                    requireNotNull(row.get("at").asString.toLongOrNull()), requireNotNull(row.get("heardMs").asString.toLongOrNull()),
                    requireNotNull(row.get("durationMs").asString.toLongOrNull()))
                validate(event)
                Row(digest, event)
            }
            require(parsed.map { it.event.eventId }.distinct().size == parsed.size)
            val enabled = root.get("enabled")?.let { require(it.asString in setOf("true", "false")); it.asBoolean } ?: true
            return State(epoch, parsed, enabled).also { memory = it }
        } catch (failure: Exception) {
            throw IOException("Local playback feedback unavailable", failure)
        }
    }

    private fun validate(event: PlaybackFeedbackEvent) {
        require(UUID.fromString(event.eventId).toString() == event.eventId)
        require(event.occurredAt > 0L && event.heardMs in 1..MAX_INTERVAL_MS && event.durationMs in 1..MAX_INTERVAL_MS)
    }

    companion object {
        private const val MAX_ROWS = 1000
        private const val MAX_BYTES = 512 * 1024
        private const val RETENTION_MS = 30L * 86_400_000L
        private const val MAX_INTERVAL_MS = 7L * 86_400_000L
        @Volatile private var instance: LocalPlaybackFeedbackStore? = null
        fun captureResetEpoch(): Long = instance?.captureEpoch ?: 0L
        fun getInstance(context: Context): LocalPlaybackFeedbackStore = instance ?: synchronized(this) {
            instance ?: (context.applicationContext ?: context).let { app ->
                LocalPlaybackFeedbackStore(File(requireNotNull(app.filesDir),
                    "unimusic-recommendations/playback-feedback-v1.json"))
            }.also { instance = it }
        }

        internal suspend fun maintain(context: Context, action: suspend (LocalPlaybackFeedbackStore) -> Unit) {
            val store = try { getInstance(context) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { return }
            try { action(store) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { store.unavailable() }
        }
    }
}
