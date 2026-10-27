package moe.ouom.neriplayer.data.sync.unified

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.unified.sync.UnifiedIdentityDataset

data class RemoteIdentitySnapshot<V>(val bytes: ByteArray?, val version: V)

interface UnifiedIdentityRemoteTransport<V> {
    suspend fun read(): RemoteIdentitySnapshot<V>
    /** False means the version changed. Implementations must never fall back to unconditional writes. */
    suspend fun compareAndSet(expected: V, bytes: ByteArray): Boolean
}

data class UnifiedIdentitySyncOutcome(
    val conditionalWrites: Int,
    val conflictRetries: Int,
    val pendingLocalChanges: Boolean,
    val liveRecordings: Int,
    val deletedRecordings: Int,
    val conflicts: Int
)

class UnifiedIdentitySyncConflictException : IOException("Unified identity sync changed repeatedly; retry later")

/** Independent conditional document. Remote evidence is durable before any attempted upload. */
class UnifiedIdentitySyncCoordinator<V>(
    private val journal: UnifiedIdentityJournal,
    private val remote: UnifiedIdentityRemoteTransport<V>
) {
    suspend fun synchronize(): UnifiedIdentitySyncOutcome = withContext(Dispatchers.Default) { withTimeout(45_000L) {
        for (attempt in 0 until 3) {
            val snapshot = remote.read()
            val canonicalRemote = snapshot.bytes?.let { bytes ->
                val data = UnifiedIdentityCodec.decode(bytes)
                journal.mergeRemote(bytes)
                UnifiedIdentityCodec.encode(UnifiedIdentityMerger.merge(data, UnifiedIdentityDataset()).dataset)
            }
            val outgoing = journal.export()
            if (canonicalRemote != null && canonicalRemote.contentEquals(outgoing)) {
                return@withTimeout outcome(outgoing, writes = 0, retries = attempt)
            }
            if (remote.compareAndSet(snapshot.version, outgoing)) {
                return@withTimeout outcome(outgoing, writes = 1, retries = attempt)
            }
            if (attempt < 2) delay((attempt + 1) * 100L)
        }
        throw UnifiedIdentitySyncConflictException()
    } }

    private suspend fun outcome(sent: ByteArray, writes: Int, retries: Int): UnifiedIdentitySyncOutcome {
        val state = journal.snapshot()
        return UnifiedIdentitySyncOutcome(writes, retries, !UnifiedIdentityCodec.encode(state.dataset).contentEquals(sent),
            state.songs.count { !it.deleted }, state.songs.count { it.deleted }, state.songs.count { it.hasConflict })
    }
}
