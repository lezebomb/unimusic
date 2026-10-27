package moe.ouom.neriplayer.data.sync.unified

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.common.io.writeTextAtomically
import moe.ouom.neriplayer.data.model.unified.sync.*

@Serializable
private data class IdentityCaptureBaseline(val songId: String, val metadata: PortableSongMetadata)

@Serializable
private data class IdentityJournalState(
    val schemaVersion: Int = 1,
    val deviceId: String,
    val counter: Long = 0,
    val dataset: UnifiedIdentityDataset = UnifiedIdentityDataset(),
    val baselines: List<IdentityCaptureBaseline> = emptyList()
)

/**
 * Atomic device-local causal journal. Same-process instances share a file mutex.
 * No network, Room application, automatic restore or playback rights are implied.
 */
class UnifiedIdentityJournal(
    private val file: File,
    private val write: (File, String) -> Unit = { target, text -> target.writeTextAtomically(text) }
) {
    private val mutex = locks.computeIfAbsent(file.canonicalPath) { Mutex() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    suspend fun snapshot(): UnifiedIdentityMergeResult = transaction { state ->
        UnifiedIdentityMerger.merge(state.dataset, UnifiedIdentityDataset())
    }

    /** Only the portable document is exported, never the private counter wrapper. */
    suspend fun export(): ByteArray {
        val dataset = snapshot().dataset
        return withContext(Dispatchers.Default) { UnifiedIdentityCodec.encode(dataset) }
    }

    suspend fun mergeRemote(bytes: ByteArray): UnifiedIdentityMergeResult {
        val remote = withContext(Dispatchers.Default) { UnifiedIdentityCodec.decode(bytes) }
        return transaction { old ->
            val merged = UnifiedIdentityMerger.merge(old.dataset, remote)
            val counter = maxOf(old.counter, merged.dataset.revisions.filter { it.dot.deviceId == old.deviceId }
                .maxOfOrNull { it.dot.counter } ?: 0L)
            if (counter != old.counter || merged.dataset != old.dataset) persist(old.copy(counter = counter, dataset = merged.dataset))
            merged
        }
    }

    /** Read-only library observations are committed together; absence never implies deletion. */
    suspend fun capture(observations: List<LocalIdentityObservation>): UnifiedIdentityCaptureResult = transaction { old ->
        val plan = UnifiedIdentityCapturePlanner.plan(old.dataset, old.baselines.associate { it.songId to it.metadata },
            old.deviceId, old.counter, observations)
        val next = old.copy(counter = plan.counter, dataset = plan.result.merged.dataset,
            baselines = plan.baselines.map { IdentityCaptureBaseline(it.key, it.value) })
        if (next != old) persist(next)
        plan.result
    }

    suspend fun upsert(songId: String, metadata: PortableSongMetadata, sources: List<PortableSongIdentity>): UnifiedIdentityMergeResult =
        change(songId, metadata, sources, allowRestore = false)

    /** Restore is deliberately distinct so passive library capture cannot revive a remote delete. */
    suspend fun restore(songId: String, metadata: PortableSongMetadata, sources: List<PortableSongIdentity>): UnifiedIdentityMergeResult =
        change(songId, metadata, sources, allowRestore = true)

    suspend fun delete(songId: String): UnifiedIdentityMergeResult = change(songId, null, emptyList(), allowRestore = false)

    private suspend fun change(songId: String, metadata: PortableSongMetadata?, sources: List<PortableSongIdentity>,
        allowRestore: Boolean): UnifiedIdentityMergeResult = transaction { old ->
        require(sources.all { it.songId == songId }) { "Source belongs to another song" }
        val candidate = old.dataset.copy(identities = (old.dataset.identities + sources).distinct())
        val baseline = UnifiedIdentityMerger.merge(candidate, UnifiedIdentityDataset())
        val canonical = requireNotNull(baseline.aliases[songId]) { "Song has no portable source" }
        val current = baseline.songs.firstOrNull { it.songId == canonical }
        require(metadata == null || current?.deleted != true || allowRestore) { "Explicit restore required" }
        if (current != null && current.concurrentRevisions.all { it.metadata == metadata }) {
            if (baseline.dataset != old.dataset) persist(old.copy(dataset = baseline.dataset))
            return@transaction baseline
        }
        require(old.counter < Long.MAX_VALUE - 1) { "Causal counter exhausted" }
        val context = mutableMapOf<String, Long>()
        current?.concurrentRevisions?.forEach { revision ->
            (revision.context + (revision.dot.deviceId to revision.dot.counter)).forEach { (actor, counter) ->
                context[actor] = maxOf(context[actor] ?: 0L, counter)
            }
        }
        val counter = old.counter + 1L
        val revision = UnifiedSongRevision(songId, UnifiedSyncDot(old.deviceId, counter), context.toSortedMap(), metadata)
        val merged = UnifiedIdentityMerger.merge(baseline.dataset,
            UnifiedIdentityDataset(identities = baseline.dataset.identities.filter { it.songId == songId }, revisions = listOf(revision)))
        persist(old.copy(counter = counter, dataset = merged.dataset))
        merged
    }

    private suspend fun <T> transaction(block: (IdentityJournalState) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(load()) }
    }

    private fun load(): IdentityJournalState {
        if (!file.exists()) return IdentityJournalState(deviceId = UUID.randomUUID().toString())
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(UnifiedIdentityCodec.MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            buffer.copyOf(size)
        }
        val text = UnifiedIdentityCodec.validateWire(bytes, setOf("schemaVersion", "deviceId", "counter", "dataset"))
        val state = json.decodeFromString<IdentityJournalState>(text)
        require(state.schemaVersion == 1) { "Unsupported identity journal" }
        require(UUID.fromString(state.deviceId).toString() == state.deviceId) { "Invalid journal actor" }
        require(state.counter in 0 until Long.MAX_VALUE) { "Invalid journal counter" }
        val merged = UnifiedIdentityMerger.merge(state.dataset, UnifiedIdentityDataset())
        require(merged.dataset.revisions.filter { it.dot.deviceId == state.deviceId }.all { it.dot.counter <= state.counter }) {
            "Identity journal counter regressed"
        }
        require(state.baselines.size <= UnifiedIdentityValidation.MAX_IDENTITIES &&
            state.baselines.map { it.songId }.distinct().size == state.baselines.size) { "Invalid local capture baseline" }
        val ids = merged.dataset.identities.mapTo(hashSetOf()) { it.songId }
        state.baselines.forEach { baseline ->
            require(baseline.songId in ids) { "Local capture baseline has no identity" }
            UnifiedIdentityValidation.validateMetadata(baseline.metadata)
        }
        return state.copy(dataset = merged.dataset)
    }

    private fun persist(state: IdentityJournalState) {
        val text = json.encodeToString(state)
        require(text.toByteArray(Charsets.UTF_8).size <= UnifiedIdentityCodec.MAX_BYTES) { "Identity journal too large" }
        // Publish no in-memory counter: failed writes leave the prior actor/counter authoritative.
        write(file, text)
    }

    private companion object { val locks = ConcurrentHashMap<String, Mutex>() }
}
