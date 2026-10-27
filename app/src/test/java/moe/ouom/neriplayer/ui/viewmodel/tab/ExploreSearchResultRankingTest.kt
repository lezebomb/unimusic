package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreSearchResultRankingTest {
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
