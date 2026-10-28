package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.common.search.FederatedSearchEngine
import moe.ouom.neriplayer.common.search.FederatedSearchProvider
import moe.ouom.neriplayer.common.search.SearchPage
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.CatalogTrack
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.SourcePlaybackMode
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import org.junit.Assert.*
import org.junit.Test

class ExploreCatalogSearchTest {
    private val track = CatalogTrack(SongSourceKey("kuwo", "1"),
        UnifiedSongMetadata("Dream", "Artist", "Album", 180_000), "https://www.kuwo.cn/play_detail/1")
    private fun song(id: Long, name: String) = SongItem(id, name, "Artist", "Album", 1, 180_000, null)

    @Test fun mixedSourcePagesCannotTurnCatalogIdsIntoPlayableNeteaseSongs() = runTest {
        val playable = ExploreSearchResult.Song(song(1, "Dream"))
        val catalog = ExploreSearchResult.Catalog(track)
        val engine = FederatedSearchEngine<ExploreSearchResult>(listOf(
            FederatedSearchProvider("netease") { _, _ -> SearchPage(listOf(playable)) },
            FederatedSearchProvider("kuwo") { _, _ -> SearchPage(listOf(catalog)) }
        ), identity = { it.stableKey })
        val result = ExploreSearchRankingSession("Dream").rank(engine.search("Dream").toList().last().interleavedItems())
        assertEquals(listOf(playable, catalog), result)
        assertEquals(listOf(playable.song), searchSongItems(result))
        assertNotEquals(playable.stableKey, catalog.stableKey)
    }

    @Test fun nativeSharingUsesPhysicalKeysAndExcludesEphemeralOrAuthenticatedMediaUrls() {
        val native = song(999L, "Fixture").copy(channelId = "qqmusic", audioId = "003OUlho2HcRHC", album = "NeteaseAlbum",
            mediaUri = "https://cdn.example.test/a.mp3?signature=fixture", streamUrl = "https://cdn.example.test/private.mp3")
        assertEquals("https://y.qq.com/n/ryqq/songDetail/003OUlho2HcRHC",
            moe.ouom.neriplayer.util.media.buildRemoteSongShareUrl(native, emptyList()))
        assertEquals("https://www.kuwo.cn/play_detail/42",
            moe.ouom.neriplayer.util.media.buildRemoteSongShareUrl(native.copy(channelId = "KUWO", audioId = "42"), emptyList()))
        assertNull(moe.ouom.neriplayer.util.media.buildRemoteSongShareUrl(native.copy(audioId = "invalid"), emptyList()))
        assertFalse(moe.ouom.neriplayer.util.media.isShareablePublicHttpUrl("https://fixture:fixture@music.163.com/#/song?id=42"))
        assertTrue(moe.ouom.neriplayer.util.media.isShareablePublicHttpUrl("https://music.163.com/#/song?id=42"))
    }

    @Test fun externalNavigationRequiresTheExactOfficialPageAndCatalogCapability() {
        assertEquals("https://www.kuwo.cn/play_detail/1", catalogExternalPageUrl(track))
        listOf("http://www.kuwo.cn/play_detail/1", "javascript:alert(1)",
            "https://www.kuwo.cn.evil.test/play_detail/1", "https://evil@www.kuwo.cn/play_detail/1",
            "https://www.kuwo.cn/play_detail/2", "https://www.kuwo.cn/play_detail/1?redirect=elsewhere").forEach { url ->
            assertNull(url, catalogExternalPageUrl(track.copy(externalPageUrl = url)))
        }
        assertEquals(track.externalPageUrl, catalogExternalPageUrl(track.copy(playbackMode = SourcePlaybackMode.AUTHORIZED_AUDIO)))
        val qq = track.copy(source = SongSourceKey("qqmusic", "003OUlho2HcRHC"),
            externalPageUrl = "https://y.qq.com/n/ryqq/songDetail/003OUlho2HcRHC")
        assertEquals(qq.externalPageUrl, catalogExternalPageUrl(qq))
        assertNull(catalogExternalPageUrl(qq.copy(externalPageUrl = qq.externalPageUrl + "?redirect=elsewhere")))
        assertNull(catalogExternalPageUrl(track.copy(source = SongSourceKey("other", "1"))))
        assertNull(catalogExternalPageUrl(track.copy(source = track.source.copy(recordingContext = "unknown"))))
    }

    @Test fun catalogIdentityKeepsSubresourceAndContextBoundaries() {
        val first = track.copy(source = SongSourceKey("kuwo", "1", "a|b", "c"))
        val second = track.copy(source = SongSourceKey("kuwo", "1", "a", "b|c"))
        assertNotEquals(ExploreSearchResult.Catalog(first).stableKey, ExploreSearchResult.Catalog(second).stableKey)
    }

    @Test fun reusedRankingSessionObservesMetadataEditsAndKeepsCatalogDiscoverable() {
        val session = ExploreSearchRankingSession("Dream")
        val first = ExploreSearchResult.Song(song(1, "Dream"))
        val second = ExploreSearchResult.Song(song(2, "Other"))
        assertEquals(first, session.rank(listOf(first, second)).first())
        val changedFirst = ExploreSearchResult.Song(first.song.copy(name = "Other"))
        val changedSecond = ExploreSearchResult.Song(second.song.copy(name = "Dream"))
        assertEquals(changedSecond, session.rank(listOf(changedFirst, changedSecond)).first())
        assertTrue(session.rank(listOf(changedFirst, ExploreSearchResult.Catalog(track))).first() is ExploreSearchResult.Catalog)
    }
}
