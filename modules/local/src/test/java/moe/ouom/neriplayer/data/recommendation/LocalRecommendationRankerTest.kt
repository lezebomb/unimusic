package moe.ouom.neriplayer.data.recommendation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.TrackStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecommendationRankerTest {
    private val day = 86_400_000L
    private val now = 1_000 * day

    @Test fun placeholderArtistsDoNotCreateFamiliarityWhileRealNamesAndExactEvidenceRemain() {
        for (artist in listOf("未知艺术家", "Unknown Artist", "A / Unknown Artist")) {
            val input = listOf(candidate("unrelated-a", artist), candidate("unrelated-b", artist))
            val history = stat(candidate("history", artist), now - 30 * day, 9_000_000, 50)
            val baseline = LocalRecommendationRanker.rank(input, emptyList(), now)
            assertEquals(baseline, LocalRecommendationRanker.rank(input, listOf(history), now))
            assertTrue(baseline.all { it.reason == LocalRecommendationReason.EXPLORE_LIBRARY })
            assertEquals("unrelated-b", LocalRecommendationRanker.rank(input, listOf(history), now,
                feedback = mapOf("unrelated-b" to LocalPlaybackSignal(repeats = 2.0))).first().identityKey)
        }
        val real = candidate("real", "Unknown Mortal Orchestra")
        val history = stat(candidate("history-real", real.song.artist), now - 30 * day, 9_000_000, 50)
        assertEquals(LocalRecommendationReason.FAMILIAR_ARTIST,
            LocalRecommendationRanker.rank(listOf(real), listOf(history), now,
                feedback = mapOf("real" to LocalPlaybackSignal(completions = 2.0))).single().reason)
    }


    @Test fun aggregatePreviewTimeAndSkipsCannotCreateArtistTasteButPositiveEvidenceCan() {
        val anchor = candidate("anchor", "Artist A")
        val related = candidate("related", "Artist A")
        val other = candidate("other", "Artist B")
        val input = listOf(anchor, related, other)
        // The aggregate has no source eligibility evidence, even if its listen total is large.
        val exposure = stat(candidate("history", "Artist A"), now - 30 * day, 90_000_000, 500)
        val baseline = LocalRecommendationRanker.rank(input, emptyList(), now)
        assertEquals(baseline, LocalRecommendationRanker.rank(input, listOf(exposure), now))
        val skipped = LocalRecommendationRanker.rank(input, listOf(exposure), now,
            feedback = mapOf("anchor" to LocalPlaybackSignal(skips = 3.0)))
        assertTrue(skipped.all { it.reason != LocalRecommendationReason.FAMILIAR_ARTIST })
        val positive = LocalRecommendationRanker.rank(input, listOf(exposure), now,
            feedback = mapOf("anchor" to LocalPlaybackSignal(completions = 2.0, repeats = 1.0)))
        assertEquals(LocalRecommendationReason.FAMILIAR_ARTIST, positive.single { it.identityKey == "related" }.reason)
        assertEquals(LocalRecommendationReason.EXPLORE_LIBRARY, positive.single { it.identityKey == "other" }.reason)
        assertEquals(positive, LocalRecommendationRanker.rank(input.reversed(), emptyList(), now,
            feedback = mapOf("anchor" to LocalPlaybackSignal(completions = 2.0, repeats = 1.0))))
    }

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

        val result = LocalRecommendationRanker.rank(familiar + others, listOf(history), now, limit = 3,
            feedback = mapOf(familiar.first().identityKey to LocalPlaybackSignal(completions = 2.0, repeats = 1.0)))

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
        val ranked = LocalRecommendationRanker.rank(listOf(fused, collab, swapped), listOf(history), now,
            feedback = mapOf("collab" to LocalPlaybackSignal(completions = 2.0, repeats = 1.0)))
        assertEquals(LocalRecommendationReason.FAMILIAR_ARTIST, ranked.first().reason)
        assertEquals(LocalRecommendationReason.EXPLORE_LIBRARY, ranked.single { it.identityKey == "fused" }.reason)
        assertEquals(setOf("collab", "swapped", "fused"), ranked.map { it.identityKey }.toSet())
        val alternatives = listOf(collab, candidate("ac", "Alpha / Gamma"), candidate("ad", "Alpha / Delta"), candidate("fresh", "Other"))
        val evidence = mapOf("collab" to LocalPlaybackSignal(completions = 2.0, repeats = 1.0))
        val top = LocalRecommendationRanker.rank(alternatives, listOf(history), now, limit = 2, feedback = evidence)
        assertTrue(top.any { it.identityKey == "fresh" })
        assertEquals(top, LocalRecommendationRanker.rank(alternatives.reversed(), listOf(history), now, limit = 2, feedback = evidence))
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

    @Test fun cancelledLargeRankingStopsWithoutChangingHealthyOrderOrReasons() {
        val library = (1..2_000).map { candidate("song-$it", "Artist ${it % 20}") }
        val expected = LocalRecommendationRanker.rank(library, emptyList(), now)
        val active = Job()
        assertEquals(expected, LocalRecommendationRanker.rank(library.reversed(), emptyList(), now,
            checkCancellation = { active.ensureActive() }))
        val cancelled = Job()
        var visited = 0
        try {
            LocalRecommendationRanker.rank(library, emptyList(), now, checkCancellation = {
                if (++visited == 50) cancelled.cancel()
                cancelled.ensureActive()
            })
            org.junit.Assert.fail("Cancelled ranking must stop")
        } catch (_: CancellationException) { }
        assertTrue(visited < library.size)
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
