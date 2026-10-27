package moe.ouom.neriplayer.data.sync.unified

import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.unified.sync.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnifiedIdentitySyncCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val a = "00000000-0000-0000-0000-000000000001"
    private val b = "00000000-0000-0000-0000-000000000002"
    private val c = "00000000-0000-0000-0000-000000000003"
    private val metadata = PortableSongMetadata("Fixture", "Artist", "Album", 180_000)
    private suspend fun add(journal: UnifiedIdentityJournal, id: String, resource: String) =
        journal.upsert(id, metadata, listOf(PortableSongIdentity(id, "netease", resource)))

    @Test fun `conditional conflict rereads merges all evidence and a second sync is a no op`() = runTest {
        val local = UnifiedIdentityJournal(File(temporary.root, "local.json"))
        val other = UnifiedIdentityJournal(File(temporary.root, "other.json"))
        add(local, a, "1");add(other, b, "2")
        var document = other.export()
        var version = 1
        var writes = 0
        val remote = object : UnifiedIdentityRemoteTransport<Int> {
            override suspend fun read() = RemoteIdentitySnapshot(document, version)
            override suspend fun compareAndSet(expected: Int, bytes: ByteArray): Boolean {
                writes++
                if (writes == 1) { add(other, c, "3");document = other.export();version++;return false }
                assertEquals(version, expected)
                document = bytes;version++;return true
            }
        }
        val outcome = UnifiedIdentitySyncCoordinator(local, remote).synchronize()
        assertEquals(1, outcome.conflictRetries);assertEquals(1, outcome.conditionalWrites)
        assertEquals(3, outcome.liveRecordings);assertFalse(outcome.pendingLocalChanges)
        assertEquals(setOf(a, b, c), UnifiedIdentityCodec.decode(document).identities.map { it.songId }.toSet())
        assertEquals(0, UnifiedIdentitySyncCoordinator(local, remote).synchronize().conditionalWrites)
        assertEquals(2, writes)
    }

    @Test fun `local changes during upload remain pending and repeated conflicts stop without losing local data`() = runTest {
        val local = UnifiedIdentityJournal(File(temporary.root, "pending.json"))
        add(local, a, "1")
        var uploaded: ByteArray? = null
        val duringUpload = object : UnifiedIdentityRemoteTransport<Int> {
            override suspend fun read() = RemoteIdentitySnapshot<Int>(null, 0)
            override suspend fun compareAndSet(expected: Int, bytes: ByteArray): Boolean {
                uploaded = bytes;add(local, b, "2");return true
            }
        }
        val result = UnifiedIdentitySyncCoordinator(local, duringUpload).synchronize()
        assertTrue(result.pendingLocalChanges)
        assertEquals(1, UnifiedIdentityCodec.decode(uploaded!!).identities.size)
        assertEquals(2, local.snapshot().songs.size)
        val before = local.export()
        var attempts = 0
        val racing = object : UnifiedIdentityRemoteTransport<Int> {
            override suspend fun read() = RemoteIdentitySnapshot<Int>(null, 0)
            override suspend fun compareAndSet(expected: Int, bytes: ByteArray): Boolean { attempts++;return false }
        }
        try { UnifiedIdentitySyncCoordinator(local, racing).synchronize();fail("Unbounded conflict retry") }
        catch (_: UnifiedIdentitySyncConflictException) { }
        assertEquals(3, attempts)
        assertArrayEquals(before, local.export())
    }
}
