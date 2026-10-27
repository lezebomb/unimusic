package moe.ouom.neriplayer.data.sync.unified

import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.unified.sync.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IdentityConflictResolutionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val songId = "00000000-0000-0000-0000-000000000001"
    private val otherId = "00000000-0000-0000-0000-000000000002"
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val metadata = PortableSongMetadata("Original", "Artist", "Album", 180_000)
    private val source = PortableSongIdentity(songId, "netease", "1")
    private fun remote(value: PortableSongMetadata?, counter: Long = 1, context: Map<String, Long> = emptyMap()) =
        UnifiedIdentityCodec.encode(UnifiedIdentityDataset(identities = listOf(source),
            revisions = listOf(UnifiedSongRevision(songId, UnifiedSyncDot(actor, counter), context, value))))

    @Test fun `explicit branch observes all concurrent versions and stale decisions never modify disk`() = runTest {
        val file = File(temporary.root, "choices.json")
        val journal = UnifiedIdentityJournal(file)
        journal.upsert(songId, metadata, listOf(source))
        journal.mergeRemote(remote(metadata.copy(title = "Remote")))
        val view = journal.decisionSnapshot()
        assertTrue(view.state.songs.single().hasConflict)
        val resolved = journal.resolveConflict(songId, view.revision, metadata.copy(title = "Remote"), emptyList())
        assertFalse(resolved.songs.single().hasConflict)
        assertEquals(2, resolved.dataset.revisions.single().context.size)
        val nextView = journal.decisionSnapshot()
        UnifiedIdentityJournal(file).upsert(otherId, metadata, listOf(PortableSongIdentity(otherId, "netease", "2")))
        val before = file.readBytes()
        try { journal.resolveConflict(songId, nextView.revision, metadata.copy(title = "Remote"), emptyList());fail("Stale choice") }
        catch (_: StaleIdentityDecisionException) { }
        assertArrayEquals(before, file.readBytes())
        journal.mergeRemote(remote(null, 2, mapOf(actor to 1)))
        val deletion = journal.decisionSnapshot()
        assertTrue(deletion.state.songs.first { it.songId == songId }.hasConflict)
        assertTrue(journal.resolveConflict(songId, deletion.revision, null, emptyList()).songs.first { it.songId == songId }.deleted)
    }

    @Test fun `choosing synced information acknowledges the displayed local edit without publishing it`() = runTest {
        val journal = UnifiedIdentityJournal(File(temporary.root, "baseline.json"))
        fun observation(value: PortableSongMetadata) = LocalIdentityObservation(songId, value, listOf(source))
        journal.capture(listOf(observation(metadata)))
        journal.mergeRemote(remote(metadata.copy(title = "Remote"), context = journal.snapshot().dataset.revisions.single().let {
            mapOf(it.dot.deviceId to it.dot.counter)
        }))
        val local = metadata.copy(title = "Offline local edit")
        val pending = journal.capture(listOf(observation(local)))
        assertEquals(listOf(songId), pending.localConflictSongIds)
        val view = journal.decisionSnapshot()
        journal.resolveConflict(songId, view.revision, metadata.copy(title = "Remote"), listOf(LocalIdentityMetadata(songId, local)))
        val again = journal.capture(listOf(observation(local)))
        assertEquals(0, again.conflictingLocalGroups);assertEquals(0, again.changedSongs)
        assertEquals("Remote", again.merged.songs.single().metadata?.title)
        assertFalse(String(journal.export()).contains("Offline local edit"))
    }
}
