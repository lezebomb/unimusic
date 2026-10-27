package moe.ouom.neriplayer.data.sync.unified

import moe.ouom.neriplayer.data.model.unified.sync.*
import org.junit.Assert.*
import org.junit.Test

class UnifiedIdentitySyncTest {
    private val a = "00000000-0000-0000-0000-000000000001"
    private val b = "00000000-0000-0000-0000-000000000002"
    private val c = "00000000-0000-0000-0000-000000000003"
    private val deviceA = "10000000-0000-0000-0000-000000000001"
    private val deviceB = "10000000-0000-0000-0000-000000000002"
    private val deviceC = "10000000-0000-0000-0000-000000000003"
    private val metadata = PortableSongMetadata("Original", "Artist", "Album", 180_000, "STUDIO")
    private fun identity(song: String, resource: String = "1") = PortableSongIdentity(song, "netease", resource)
    private fun revision(song: String, device: String, counter: Long = 1, context: Map<String, Long> = emptyMap(), value: PortableSongMetadata? = metadata) =
        UnifiedSongRevision(song, UnifiedSyncDot(device, counter), context, value)
    private fun dataset(id: PortableSongIdentity, revision: UnifiedSongRevision) =
        UnifiedIdentityDataset(identities = listOf(id), revisions = listOf(revision))
    private fun reject(block: () -> Unit) {
        try { block();fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `exact sources alias but titles and bili parts never do`() {
        val left = dataset(identity(a), revision(a, deviceA))
        val right = dataset(identity(b), revision(b, deviceB))
        val merged = UnifiedIdentityMerger.merge(left, right)
        assertEquals(mapOf(a to a, b to a), merged.aliases)
        assertEquals(1, merged.songs.size)
        assertFalse(merged.songs.single().hasConflict)
        val other = UnifiedIdentityMerger.merge(left, dataset(identity(c, "2"), revision(c, deviceC)))
        assertEquals(2, other.songs.size)
        val part1 = dataset(PortableSongIdentity(a, "bilibili", "BV1234567890", "1"), revision(a, deviceA))
        val part2 = dataset(PortableSongIdentity(b, "bilibili", "BV1234567890", "2"), revision(b, deviceB))
        assertEquals(2, UnifiedIdentityMerger.merge(part1, part2).songs.size)
    }

    @Test fun `offline delete wins concurrent edit and only observed explicit restore revives`() {
        val deletion = dataset(identity(a), revision(a, deviceA, value = null))
        val edit = dataset(identity(b), revision(b, deviceB, value = metadata.copy(title = "Edited")))
        val merged = UnifiedIdentityMerger.merge(deletion, edit)
        assertTrue(merged.songs.single().deleted)
        assertNull(merged.songs.single().metadata)
        assertTrue(merged.songs.single().hasConflict)
        assertEquals(2, merged.dataset.identities.size)
        val restore = dataset(identity(b), revision(b, deviceB, 2, mapOf(deviceA to 1, deviceB to 1)))
        val restored = UnifiedIdentityMerger.merge(merged.dataset, restore)
        assertFalse(restored.songs.single().deleted)
        assertEquals(metadata, restored.songs.single().metadata)
        assertEquals(1, restored.dataset.revisions.size)
        assertFalse(UnifiedIdentityMerger.merge(restored.dataset, deletion).songs.single().deleted)
        reject { UnifiedIdentityMerger.merge(merged.dataset, dataset(identity(b), revision(b, deviceB, 2))) }
    }

    @Test fun `merge is commutative associative idempotent and rejects forged causal histories`() {
        val left = dataset(identity(a), revision(a, deviceA))
        val middle = UnifiedIdentityDataset(identities = listOf(identity(b), PortableSongIdentity(b, "qqmusic", "mid1")),
            revisions = listOf(revision(b, deviceB, value = metadata.copy(album = "Alternate"))))
        val right = dataset(PortableSongIdentity(c, "qqmusic", "mid1"), revision(c, deviceC, value = null))
        fun merge(x: UnifiedIdentityDataset, y: UnifiedIdentityDataset) = UnifiedIdentityMerger.merge(x, y).dataset
        assertEquals(merge(left, middle), merge(middle, left))
        val normalized = merge(merge(left, middle), right)
        assertEquals(normalized, merge(left, merge(middle, right)))
        assertEquals(normalized, merge(normalized, normalized))
        assertArrayEquals(UnifiedIdentityCodec.encode(normalized), UnifiedIdentityCodec.encode(merge(right, merge(middle, left))))
        reject { merge(left, dataset(identity(b), revision(b, deviceA))) }
        val cycleA = dataset(identity(a), revision(a, deviceA, context = mapOf(deviceB to 1)))
        val cycleB = dataset(identity(b), revision(b, deviceB, context = mapOf(deviceA to 1)))
        reject { merge(cycleA, cycleB) }
    }

    @Test fun `strict codec rejects credentials unknown protocols duplicates and private source identities`() {
        val original = dataset(identity(a), revision(a, deviceA))
        val wire = UnifiedIdentityCodec.encode(original)
        assertEquals(original, UnifiedIdentityCodec.decode(wire))
        val text = String(wire, Charsets.UTF_8)
        assertFalse(text.contains("availability"));assertFalse(text.contains("Cookie"));assertFalse(text.contains("audioUrl"))
        reject { UnifiedIdentityCodec.decode(text.replace("\"schemaVersion\":1", "\"schemaVersion\":99").toByteArray()) }
        reject { UnifiedIdentityCodec.decode(text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").toByteArray()) }
        reject { UnifiedIdentityCodec.decode(text.replace("\"title\":", "\"Cookie\":\"private\",\"title\":").toByteArray()) }
        reject { UnifiedIdentityCodec.decode("{}".toByteArray()) }
        reject { UnifiedIdentityCodec.decode(text.replace("\"metadata\":{", "\"hiddenPayload\":{").toByteArray()) }
        reject { UnifiedIdentityCodec.decode(ByteArray(UnifiedIdentityCodec.MAX_BYTES + 1)) }
        listOf(identity(a).copy(provider = "legacy"), identity(a).copy(resourceId = "https://private/?token=value"),
            identity(a).copy(provider = "local"), PortableSongIdentity(a, "bilibili", "BV1234567890"),
            identity(a).copy(recordingContext = "unresolved:device-id")).forEach { source ->
            reject { UnifiedIdentityCodec.encode(original.copy(identities = listOf(source))) }
        }
    }
}
