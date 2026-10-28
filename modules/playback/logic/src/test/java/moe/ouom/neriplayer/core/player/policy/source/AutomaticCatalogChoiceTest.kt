package moe.ouom.neriplayer.core.player.policy.source

import moe.ouom.neriplayer.data.model.unified.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticCatalogChoiceTest {
    private val expected = RecordingMetadata("Song", "Artist", 200_000, versionContext = "Album")
    private fun track(id: String, title: String = "Song", artist: String = "Artist", duration: Long = 200_000, album: String = "Album") =
        CatalogTrack(SongSourceKey("kuwo", id), UnifiedSongMetadata(title, artist, album, duration), null)

    @Test fun nativeFailuresChooseTwoOtherPlatformsIncludingNeteaseWithoutSearchingItself() {
        assertEquals(listOf("netease", "kuwo"), nativeAlternateProviderIds("qqmusic"))
        assertEquals(listOf("netease", "qqmusic"), nativeAlternateProviderIds("kuwo"))
        assertEquals(listOf("qqmusic", "kuwo"), nativeAlternateProviderIds("netease"))
        for (original in listOf("qqmusic", "kuwo", "netease", "bilibili", null)) {
            val ids = nativeAlternateProviderIds(original)
            assertEquals(2, ids.distinct().size);assertFalse(original in ids)
        }
    }

    @Test fun wrongRecordingNeverWinsAndDuplicateEvidenceNeedsDisambiguation() {
        val valid = track("1")
        assertEquals(valid.source, automaticCatalogChoice(expected, listOf(track("2", "Song (Live)"),
            track("3", artist = "Cover Singer"), track("4", duration = 30_000), valid), null)!!.key)
        assertNull(automaticCatalogChoice(expected, listOf(valid, track("2")), null))
        assertEquals(valid.source, automaticCatalogChoice(expected, listOf(valid, track("2", album = "Other")), null)!!.key)
        assertNull(automaticCatalogChoice(expected, listOf(valid), valid.source))
    }

    @Test fun readyFirstButNeverAheadOfRecordingGatesOrUserRejection() {
        val values = listOf(track("1"), track("2"), track("3"), track("4", "Song (Live)"), track("5"))
            .map { RecordingSourceCandidate(it.source, it.metadata) }
        val checks = mapOf(values[0].key to SourceCheckResult(SourceCheckStatus.NO_PERMISSION, 1000),
            values[2].key to SourceCheckResult(SourceCheckStatus.ADDRESS_RESOLVED, 1000),
            values[3].key to SourceCheckResult(SourceCheckStatus.ADDRESS_RESOLVED, 1000),
            values[4].key to SourceCheckResult(SourceCheckStatus.ADDRESS_RESOLVED, 1000))
        val decisions = mapOf(values[4].key to SourceMatchDecision.REJECTED)
        assertEquals(listOf("3", "2", "1", "4", "5"), orderRecordingSources(expected, values, decisions, checks, 1000).map { it.key.resourceId })
        assertEquals("1", orderRecordingSources(expected, values.take(3), emptyMap(), checks, 122_000).first().key.resourceId)
    }
}
