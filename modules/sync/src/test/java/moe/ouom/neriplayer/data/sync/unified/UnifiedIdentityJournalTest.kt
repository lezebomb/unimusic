package moe.ouom.neriplayer.data.sync.unified

import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.unified.sync.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnifiedIdentityJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val a = "00000000-0000-0000-0000-000000000001"
    private val b = "00000000-0000-0000-0000-000000000002"
    private val c = "00000000-0000-0000-0000-000000000003"
    private val metadata = PortableSongMetadata("Song", "Artist", "Album", 180_000)
    private fun source(id: String, resource: String = "1") = listOf(PortableSongIdentity(id, "netease", resource))

    @Test fun `reopen and concurrent instances never reuse the persisted device counter`() = runTest {
        val file = File(temporary.root, "journal.json")
        val first = UnifiedIdentityJournal(file)
        val initial = first.upsert(a, metadata, source(a))
        val device = initial.dataset.revisions.single().dot.deviceId
        val reopened = UnifiedIdentityJournal(file)
        val writes = listOf(async { first.upsert(b, metadata, source(b, "2")) }, async { reopened.upsert(c, metadata, source(c, "3")) })
        writes.forEach { it.await() }
        val snapshot = UnifiedIdentityJournal(file).snapshot()
        assertEquals(setOf(device), snapshot.dataset.revisions.map { it.dot.deviceId }.toSet())
        assertEquals(setOf(1L, 2L, 3L), snapshot.dataset.revisions.map { it.dot.counter }.toSet())
        val before = file.readBytes()
        reopened.upsert(a, metadata, source(a))
        assertArrayEquals(before, file.readBytes())
        assertEquals(snapshot.dataset, UnifiedIdentityCodec.decode(first.export()))
        assertFalse(String(first.export()).contains("\"dataset\""))
    }

    @Test fun `remote tombstone blocks passive upsert and explicit restore observes both devices`() = runTest {
        val left = UnifiedIdentityJournal(File(temporary.root, "left.json"))
        val right = UnifiedIdentityJournal(File(temporary.root, "right.json"))
        left.upsert(a, metadata, source(a))
        right.mergeRemote(left.export())
        right.delete(a)
        left.mergeRemote(right.export())
        assertTrue(left.snapshot().songs.single().deleted)
        val before = left.export()
        try { left.upsert(a, metadata, source(a));fail("Passive resurrection") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, left.export())
        left.restore(a, metadata.copy(title = "Restored"), source(a))
        val restored = right.mergeRemote(left.export())
        assertFalse(restored.songs.single().deleted)
        assertEquals("Restored", restored.songs.single().metadata?.title)
        assertEquals(2, restored.dataset.revisions.single().context.size)
        assertEquals(1, restored.dataset.revisions.size)
    }

    @Test fun `failed writes future schemas and regressed counters leave the old document intact`() = runTest {
        val file = File(temporary.root, "safe.json")
        val journal = UnifiedIdentityJournal(file)
        journal.upsert(a, metadata, source(a))
        val original = file.readBytes()
        val failing = UnifiedIdentityJournal(file, write = { _, _ -> throw IOException("simulated full disk") })
        try { failing.upsert(a, metadata.copy(title = "New"), source(a));fail("Expected IO failure") } catch (_: IOException) { }
        assertArrayEquals(original, file.readBytes())
        val next = journal.upsert(a, metadata.copy(title = "New"), source(a))
        assertEquals(2L, next.dataset.revisions.single().dot.counter)
        val valid = file.readText()
        listOf(valid.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":99"),
            valid.replaceFirst("\"counter\":2", "\"counter\":0")).forEach { invalid ->
            file.writeText(invalid)
            try { UnifiedIdentityJournal(file).delete(a);fail("Expected refusal") } catch (_: IllegalArgumentException) { }
            assertEquals(invalid, file.readText())
        }
    }
}
