package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.unified.CatalogTrack
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.SourceCheckResult
import moe.ouom.neriplayer.data.model.unified.SourceCheckStatus
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata

class ExploreSearchResultRankingTest {
    @Test fun verifiedNeteaseAddressReordersOnlyMatchingProviderAndVersionWhileChecksAreFresh() {
        fun item(id: Long, name: String = "Dream") = ExploreSearchResult.Song(song(id, name, "Artist")
            .copy(channelId = "netease", audioId = id.toString(), album = "Album"))
        val denied = item(1);val unknown = item(2);val ready = item(3);val live = item(4, "Dream (Live)")
        val now = System.currentTimeMillis()
        val checks = mapOf(SongSourceKey("netease", "1") to SourceCheckResult(SourceCheckStatus.NO_PERMISSION, now),
            SongSourceKey("netease", "3") to SourceCheckResult(SourceCheckStatus.ADDRESS_RESOLVED, now),
            SongSourceKey("netease", "4") to SourceCheckResult(SourceCheckStatus.ADDRESS_RESOLVED, now))
        val session = ExploreSearchRankingSession("Dream")
        assertEquals(listOf(ready, unknown, denied, live), session.rank(listOf(denied, live, unknown, ready), checks))
        assertEquals(listOf(denied, unknown, ready), session.rank(listOf(denied, unknown, ready),
            checks.mapValues { (_, check) -> check.copy(checkedAtMillis = now - 120_001) }))
        assertEquals(null, searchAvailabilityCandidate(ready.copy(song = ready.song.copy(channelId = "bilibili"))))
        assertEquals(null, searchAvailabilityCandidate(ready.copy(song = ready.song.copy(channelId = null))))
        assertEquals(null, searchAvailabilityCandidate(ready.copy(song = ready.song.copy(streamUrl = "https://fixture.test/audio.mp3"))))
    }

    @Test fun fullVersionEvidenceOutranksFreshAudioAddressForAnUnqualifiedTitle() {
        fun catalog(id: String, album: String = "Album", version: moe.ouom.neriplayer.data.model.unified.RecordingVersion =
            moe.ouom.neriplayer.data.model.unified.RecordingVersion.UNKNOWN) = ExploreSearchResult.Catalog(
            CatalogTrack(SongSourceKey("kuwo", id), UnifiedSongMetadata("Dream", "Artist", album, 180_000, version), null))
        val studio = catalog("1")
        val explicitLive = catalog("2", version = moe.ouom.neriplayer.data.model.unified.RecordingVersion.LIVE)
        val albumCover = catalog("3", album = "Acoustic Cover")
        val checks = listOf(explicitLive, albumCover).associate { it.track.source to
            SourceCheckResult(SourceCheckStatus.ADDRESS_RESOLVED, System.currentTimeMillis()) }
        val session = ExploreSearchRankingSession("Dream")
        assertEquals(listOf(studio, explicitLive, albumCover), session.rank(listOf(explicitLive, albumCover, studio), checks))
        // Updating metadata under the same source key must also replace its cached version score.
        val corrected = explicitLive.copy(track = explicitLive.track.copy(metadata = explicitLive.track.metadata.copy(
            version = moe.ouom.neriplayer.data.model.unified.RecordingVersion.STUDIO)))
        assertEquals(corrected, session.rank(listOf(studio, corrected), checks).first())
    }

    @Test fun albumVersionLabelsAndSyntheticVoiceRemainRelevantToTheRequestedVersion() {
        val original = song(1, "Dream", "Artist")
        val live = original.copy(id = 2, album = "Live")
        val unrelated = original.copy(id = 3, name = "Other", artist = "Dream", album = "Live")
        assertEquals(live, rankExploreSongSearchResults("Dream Live", listOf(unrelated, original, live)).first())
        val human = original.copy(id = 4, name = "Dream (Cover)")
        val synthetic = human.copy(id = 5, album = "AI vocals")
        assertEquals(human, rankExploreSongSearchResults("Dream Cover", listOf(synthetic, human)).first())
        assertEquals(synthetic, rankExploreSongSearchResults("Dream AI vocals", listOf(human, synthetic)).first())
    }

    @Test fun verifiedNativeAddressRanksAboveEqualUnknownOrDeniedSourcesWithoutPromotingLive() {
        fun catalog(id: String, title: String = "Dream") = ExploreSearchResult.Catalog(
            moe.ouom.neriplayer.data.model.unified.CatalogTrack(
                moe.ouom.neriplayer.data.model.unified.SongSourceKey("kuwo", id),
                moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata(title, "Artist", "Album", 180_000), null))
        val unknown = catalog("1")
        val denied = catalog("2")
        val ready = catalog("3")
        val live = catalog("4", "Dream (Live)")
        val time = System.currentTimeMillis()
        val checks = mapOf(denied.track.source to moe.ouom.neriplayer.data.model.unified.SourceCheckResult(
            moe.ouom.neriplayer.data.model.unified.SourceCheckStatus.NO_PERMISSION, time),
            ready.track.source to moe.ouom.neriplayer.data.model.unified.SourceCheckResult(
                moe.ouom.neriplayer.data.model.unified.SourceCheckStatus.ADDRESS_RESOLVED, time),
            live.track.source to moe.ouom.neriplayer.data.model.unified.SourceCheckResult(
                moe.ouom.neriplayer.data.model.unified.SourceCheckStatus.ADDRESS_RESOLVED, time))
        assertEquals(listOf(ready, unknown, denied, live), ExploreSearchRankingSession("Dream").rank(
            listOf(denied, live, unknown, ready), checks))
    }
    @Test
    fun `pinyin query promotes matching Chinese song title`() {
        val songs = listOf(
            song(id = 1, name = "夜曲"),
            song(id = 2, name = "晴天")
        )

        val ranked = rankExploreSongSearchResults("qt", songs)

        assertEquals("晴天", ranked.first().name)
    }

    @Test
    fun `title pinyin match ranks ahead of artist pinyin match`() {
        val songs = listOf(
            song(id = 1, name = "别的歌", artist = "鹿乃"),
            song(id = 2, name = "鹿乃", artist = "别的歌")
        )

        val ranked = rankExploreSongSearchResults("lunai", songs)

        assertEquals("鹿乃", ranked.first().name)
    }

    @Test
    fun `latin search ignores case in song metadata`() {
        val songs = listOf(
            song(id = 1, name = "Intro"),
            song(id = 2, name = "LOFI Dream")
        )

        val ranked = rankExploreSongSearchResults("lofi", songs)

        assertEquals("LOFI Dream", ranked.first().name)
    }

    @Test
    fun `bare title prefers unmodified recording while explicit version stays discoverable`() {
        val songs = listOf(song(1, "晴天 (Live)"), song(2, "晴天 (翻唱)"), song(3, "晴天"))
        assertEquals(3L, rankExploreSongSearchResults("晴天", songs).first().id)
        assertEquals(1L, rankExploreSongSearchResults("晴天 Live", songs).first().id)
        assertEquals(2L, rankExploreSongSearchResults("晴天 翻唱", songs).first().id)
    }

    @Test
    fun `equal metadata from different sources remains separate and stable`() {
        val songs = listOf(song(1, "Dream").copy(channelId = "netease"),
            song(1, "Dream").copy(channelId = "bilibili"))
        assertEquals(songs, rankExploreSongSearchResults("Ｄｒｅａｍ", songs))
    }

    @Test
    fun `version preference does not promote an artist match over the requested song title`() {
        val requested = song(1, "晴天 (Live)", "周杰伦")
        val unrelated = song(2, "完全不同的歌", "晴天")
        assertEquals(requested, rankExploreSongSearchResults("晴天", listOf(unrelated, requested)).first())
    }

    @Test fun stableSongKeysKeepSeparatorsAndMissingFieldsInTheirOwnComponents() {
        val first = song(1, "Original").copy(channelId = "bilibili", audioId = "one|two", subAudioId = "three")
        val second = first.copy(audioId = "one", subAudioId = "two|three")
        org.junit.Assert.assertNotEquals(ExploreSearchResult.Song(first).stableKey, ExploreSearchResult.Song(second).stableKey)
        val absentChannel = first.copy(channelId = null, audioId = "local", subAudioId = null)
        val absentAudio = first.copy(channelId = "local", audioId = null, subAudioId = null)
        org.junit.Assert.assertNotEquals(ExploreSearchResult.Song(absentChannel).stableKey, ExploreSearchResult.Song(absentAudio).stableKey)
        assertEquals(ExploreSearchResult.Song(first).stableKey, ExploreSearchResult.Song(first.copy(name = "Display edit")).stableKey)
    }

    @Test fun cancelledScoringReleasesTheSessionAndCannotReturnAPartialOrder() {
        val session = ExploreSearchRankingSession("Dream")
        val items = (1L..300L).map { ExploreSearchResult.Song(song(it, "Dream $it")) }
        val job = kotlinx.coroutines.Job()
        var checkpoints = 0
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            session.rank(items) {
                if (++checkpoints == 3) job.cancel()
                job.ensureActive()
            }
        }
        assertEquals(300, session.rank(items).size)
    }

    @Test fun aProbeFinishingWhileMetadataIsRankedCannotBeOverwrittenByTheOldOrder() = runTest {
        fun candidate(id: String) = ExploreSearchResult.Catalog(CatalogTrack(SongSourceKey("kuwo", id),
            UnifiedSongMetadata("Dream", "Artist", "Album", 180_000), null))
        val first = candidate("1")
        val second = candidate("2")
        var checks = mapOf(first.track.source to SourceCheckResult(SourceCheckStatus.ADDRESS_RESOLVED, System.currentTimeMillis()))
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            ExploreSearchRankingSession("Dream").rankWithCurrentChecks(listOf(first, second), { checks }, { true },
                StandardTestDispatcher(testScheduler))
        }
        // The metadata batch has captured the old map and is queued on its worker when the probe completes.
        checks = mapOf(first.track.source to SourceCheckResult(SourceCheckStatus.NO_PERMISSION, System.currentTimeMillis()),
            second.track.source to SourceCheckResult(SourceCheckStatus.ADDRESS_RESOLVED, System.currentTimeMillis()))
        assertEquals(listOf(second, first), result.await())
    }

    @Test fun aReplacedQueryDoesNotPublishItsQueuedRanking() = runTest {
        var current = true
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            ExploreSearchRankingSession("Dream").rankWithCurrentChecks(listOf(ExploreSearchResult.Song(song(1, "Dream"))),
                { emptyMap() }, { current }, StandardTestDispatcher(testScheduler))
        }
        current = false
        org.junit.Assert.assertNull(result.await())
    }

    private fun song(id: Long, name: String, artist: String = "周杰伦"): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = "测试专辑",
            albumId = id,
            durationMs = 180_000L,
            coverUrl = null
        )
    }
}
