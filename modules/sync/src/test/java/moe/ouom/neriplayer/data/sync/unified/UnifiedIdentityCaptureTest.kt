package moe.ouom.neriplayer.data.sync.unified

import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.unified.sync.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnifiedIdentityCaptureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val a = "00000000-0000-0000-0000-000000000001"
    private val b = "00000000-0000-0000-0000-000000000002"
    private val metadata = PortableSongMetadata("Original", "Artist", "Album", 180_000)
    private fun observation(id: String = a, title: String = "Original", resource: String = "1") =
        LocalIdentityObservation(id, metadata.copy(title = title), listOf(PortableSongIdentity(id, "netease", resource)))

    @Test fun `batch capture writes once and unchanged local rows never undo remote edits or deletion`() = runTest {
        val file = File(temporary.root, "capture.json")
        var writes = 0
        val local = UnifiedIdentityJournal(file, write = { target, text -> writes++;target.writeText(text) })
        val first = local.capture(listOf(observation(), observation(b, resource = "2")))
        assertEquals(2, first.changedSongs);assertEquals(1, writes)
        val reopened = UnifiedIdentityJournal(file, write = { target, text -> writes++;target.writeText(text) })
        assertEquals(0, reopened.capture(listOf(observation(), observation(b, resource = "2"))).changedSongs)
        assertEquals(1, writes)
        val remote = UnifiedIdentityJournal(File(temporary.root, "remote.json"))
        remote.mergeRemote(local.export())
        remote.upsert(a, metadata.copy(title = "Remote"), observation().sources)
        reopened.mergeRemote(remote.export())
        val captured = reopened.capture(listOf(observation(), observation(b, resource = "2")))
        assertEquals(0, captured.changedSongs)
        assertEquals("Remote", captured.merged.songs.first { it.songId == a }.metadata?.title)
        val conflict = reopened.capture(listOf(observation(title = "Offline local")))
        assertEquals(0, conflict.changedSongs);assertEquals(1, conflict.conflictingLocalGroups)
        assertEquals("Remote", conflict.merged.songs.first { it.songId == a }.metadata?.title)
        remote.delete(a);reopened.mergeRemote(remote.export())
        val deleted = reopened.capture(listOf(observation()))
        assertEquals(1, deleted.suppressedDeletedSongs)
        assertTrue(deleted.merged.songs.first { it.songId == a }.deleted)
        val portable = String(reopened.export())
        assertFalse(portable.contains("baselines"));assertFalse(portable.contains("Offline local"))
    }

    @Test fun `actual unambiguous local changes advance once while absence is never a tombstone`() = runTest {
        val journal = UnifiedIdentityJournal(File(temporary.root, "change.json"))
        journal.capture(listOf(observation()))
        val changed = journal.capture(listOf(observation(title = "Local edited")))
        assertEquals(1, changed.changedSongs)
        assertEquals(2L, changed.merged.dataset.revisions.single().dot.counter)
        assertEquals("Local edited", changed.merged.songs.single().metadata?.title)
        val empty = journal.capture(emptyList())
        assertEquals(0, empty.changedSongs);assertFalse(empty.merged.songs.single().deleted)
        assertEquals(0, journal.capture(listOf(observation(title = "Local edited"))).changedSongs)
    }
}
