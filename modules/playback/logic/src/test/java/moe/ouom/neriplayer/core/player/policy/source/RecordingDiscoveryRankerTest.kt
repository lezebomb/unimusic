package moe.ouom.neriplayer.core.player.policy.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDiscoveryRankerTest {
    private val expected = RecordingMetadata("晴天", "周杰伦", 269_000)
    private fun candidate(id: String, metadata: RecordingMetadata, relevance: Int = 0) =
        RecordingDiscoveryCandidate(id, metadata, id, relevance)

    @Test fun multipleQualifiedUploadsNeedManualChoiceRegardlessOfTextScore() {
        val one = candidate("one", expected, 1)
        val two = candidate("two", expected.copy(durationMs = 270_000), 999)
        assertTrue(RecordingDiscoveryRanker.unambiguous(expected, listOf(one, two)).isEmpty())
        assertEquals(listOf("one"), RecordingDiscoveryRanker.unambiguous(expected,
            listOf(one, one, candidate("cover", expected.copy(title = "晴天 (Cover)"), 999))))
        assertTrue(RecordingDiscoveryRanker.unambiguous(expected, listOf(candidate("unknown", expected.copy(durationMs = 0)))).isEmpty())
    }

    @Test fun conflictingVersionsCannotFillTheDiscoveryWindowAheadOfTheOriginal() {
        val covers = (1..10).map { candidate("cover-$it", expected.copy(title = "晴天 (翻唱)"), 999) }
        val ranked = RecordingDiscoveryRanker.rank(expected, covers + candidate("original", expected, 1))
        assertEquals(listOf("original"), ranked)
    }

    @Test fun sameLengthAloneNeverOutranksCompleteRecordingMetadata() {
        val ranked = RecordingDiscoveryRanker.rank(expected, listOf(
            candidate("other-title", expected.copy(title = "雨天"), 999),
            candidate("other-artist", expected.copy(artist = "别人"), 999),
            candidate("closest", expected.copy(durationMs = 269_500), 5),
            candidate("farther", expected.copy(durationMs = 272_000), 999)
        ))
        assertEquals(listOf("closest", "farther"), ranked.take(2))
    }

    @Test fun discoveryRetainsUnknownContextForDetailChecksWithoutApprovingIt() {
        val target = expected.copy(title = "晴天 (Live)")
        val unknown = expected.copy(durationMs = 0)
        val candidates = listOf(candidate("unknown", unknown), candidate("remix", expected.copy(title = "晴天 (Remix)")))
        assertEquals(listOf("unknown"), RecordingDiscoveryRanker.rank(target, candidates))
        assertTrue(!RecordingMatchPolicy.assess(target, unknown).accepted)
    }
}
