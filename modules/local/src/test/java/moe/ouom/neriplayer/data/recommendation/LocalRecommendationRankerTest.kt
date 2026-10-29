package moe.ouom.neriplayer.data.recommendation

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.TrackStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecommendationRankerTest {
    private val day = 86_400_000L
    private val now = 1_000 * day

    @Test
    fun `ranking deduplicates identities and keeps distinct recordings with the same title`() {
        val remote = candidate("recording-a", "Artist A", LocalRecommendationSource.NETEASE)
        val local = remote.copy(source = LocalRecommendationSource.LOCAL_FILE, song = remote.song.copy(localFilePath = "/music/a.flac"))
        val otherRecording = candidate("recording-b", "Artist B", LocalRecommendationSource.BILIBILI)
        val input = listOf(remote.copy(favorite = true), local, otherRecording)

        val first = LocalRecommendationRanker.rank(input, emptyList(), now)
        val reordered = LocalRecommendationRanker.rank(input.reversed(), emptyList(), now + day / 2)

        assertEquals(first, reordered)
        assertEquals(2, first.size)
        assertEquals(setOf("recording-a", "recording-b"), first.map { it.identityKey }.toSet())
        assertEquals(LocalRecommendationSource.LOCAL_FILE, first.single { it.identityKey == "recording-a" }.source)
        assertEquals(LocalRecommendationSource.BILIBILI, first.single { it.identityKey == "recording-b" }.source)
        assertTrue(first.all { it.reason == LocalRecommendationReason.EXPLORE_LIBRARY })
    }

    @Test
    fun `recent listening is lowered while older listening earns a rediscovery reason`() {
        val recent = candidate("recent", "Recent Artist")
        val old = candidate("old", "Old Artist")
        val unknown = candidate("unknown", "New Artist")
        val statistics = listOf(stat(recent, now - day / 4, 180_000, 1), stat(old, now - 30 * day, 180_000, 1))

        val result = LocalRecommendationRanker.rank(listOf(recent, old, unknown), statistics, now)

        assertEquals("recent", result.last().identityKey)
        assertEquals(LocalRecommendationReason.REDISCOVER, result.single { it.identityKey == "old" }.reason)
        assertEquals(LocalRecommendationReason.EXPLORE_LIBRARY, result.single { it.identityKey == "unknown" }.reason)
    }

    @Test
    fun `familiar artist affinity does not fill the first recommendations with one artist`() {
        val familiar = (1..4).map { candidate("familiar-$it", "Familiar Artist") }
        val others = listOf(candidate("other-a", "Artist B"), candidate("other-b", "Artist C"))
        val history = stat(candidate("history", "Familiar Artist"), now - 30 * day, 9_000_000, 50)

        val result = LocalRecommendationRanker.rank(familiar + others, listOf(history), now, limit = 3)

        assertEquals(3, result.map { it.song.artist }.distinct().size)
        assertEquals(LocalRecommendationReason.FAMILIAR_ARTIST, result.first().reason)
        assertEquals(3, result.map { it.identityKey }.distinct().size)
    }

    @Test fun `explicit playback evidence affects only the supplied exact identity`() {
        val a = candidate("a", "Artist A")
        val b = candidate("b", "Artist B")
        val input = listOf(a, b)
        val baseline = LocalRecommendationRanker.rank(input, emptyList(), now)
        assertEquals(baseline, LocalRecommendationRanker.rank(input, emptyList(), now,
            feedback = mapOf("unrelated" to LocalPlaybackSignal(skips = 3.0))))
        assertEquals("b", LocalRecommendationRanker.rank(input, emptyList(), now,
            feedback = mapOf("a" to LocalPlaybackSignal(skips = 3.0))).first().identityKey)
        assertEquals("a", LocalRecommendationRanker.rank(input, emptyList(), now,
            feedback = mapOf("a" to LocalPlaybackSignal(repeats = 2.0))).first().identityKey)
    }

    @Test fun collaborativeCreditsShareAffinityAndDiversityWithoutMergingSingleArtistNames() {
        val collab = candidate("collab", "Alpha / Beta")
        val swapped = candidate("swapped", "Beta & Alpha")
        val fused = candidate("fused", "AlphaBeta")
        val history = stat(candidate("history", "Beta / Alpha"), now - 30 * day, 9_000_000, 50)
        val ranked = LocalRecommendationRanker.rank(listOf(fused, collab, swapped), listOf(history), now)
        assertEquals(LocalRecommendationReason.FAMILIAR_ARTIST, ranked.first().reason)
        assertEquals(LocalRecommendationReason.EXPLORE_LIBRARY, ranked.single { it.identityKey == "fused" }.reason)
        assertEquals(setOf("collab", "swapped", "fused"), ranked.map { it.identityKey }.toSet())
        val alternatives = listOf(collab, candidate("ac", "Alpha / Gamma"), candidate("ad", "Alpha / Delta"), candidate("fresh", "Other"))
        val top = LocalRecommendationRanker.rank(alternatives, listOf(history), now, limit = 2)
        assertTrue(top.any { it.identityKey == "fresh" })
        assertEquals(top, LocalRecommendationRanker.rank(alternatives.reversed(), listOf(history), now, limit = 2))
    }

    @Test fun retiredOnlineSourcesLeaveRecommendationsWhilePlayableLocalCopiesRemain() {
        val qq = candidate("qq", "Artist").let { it.copy(song = it.song.copy(channelId = "qqmusic")) }
        val yt = candidate("yt", "Artist", LocalRecommendationSource.YOUTUBE_MUSIC)
        val local = yt.copy(identityKey = "downloaded", source = LocalRecommendationSource.LOCAL_FILE,
            song = yt.song.copy(channelId = "youtubeMusic", localFilePath = "/music/owned.mp3"))
        val active = candidate("active", "Artist", LocalRecommendationSource.NETEASE)
        val input = listOf(qq, yt, local, active)
        assertEquals(setOf("downloaded", "active"), LocalRecommendationRanker.rank(input, emptyList(), now).map { it.identityKey }.toSet())
        assertEquals(setOf("downloaded", "active"), LocalRecommendationRanker.canonicalCandidates(input).map { it.identityKey }.toSet())
    }

    private fun candidate(key: String, artist: String, source: LocalRecommendationSource = LocalRecommendationSource.LIBRARY) =
        LocalRecommendationCandidate(
            SongItem(key.hashCode().toLong(), "Same title", artist, "album", 0, 180_000, null), key, source
        )

    private fun stat(candidate: LocalRecommendationCandidate, lastPlayed: Long, listenMs: Long, plays: Int) =
        candidate.song.let { song ->
            TrackStat(song.id, song.name, song.artist, song.album, song.albumId, song.coverUrl, song.durationMs,
                listenMs, plays, lastPlayed, lastPlayed, null, null, null, null, null, null, candidate.identityKey)
        }
}
