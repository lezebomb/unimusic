package moe.ouom.neriplayer.ui.viewmodel.tab.search

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExploreSearchArtistIntentTest {
    private val query = "哭泣少女乐队"
    private fun songs(): List<ExploreSearchResult> {
        val raw = javaClass.getResourceAsStream("/unimusic/search/girls-band-cry-songs.json")!!.bufferedReader().use { it.readText() }
        return parseNeteaseSearchResults(JSONObject(raw), NeteaseExploreSearchType.SONG).items
    }
    private fun artists(): List<NeteaseSearchArtistResult> = parseNeteaseSearchResults(JSONObject("""{"code":200,"result":{"artists":[
        {"id":57020536,"name":"トゲナシトゲアリ","alias":["TOGENASHI TOGEARI"],"transNames":["无刺有刺"]},
        {"id":62046853,"name":"ダイヤモンドダスト","alias":["DIAMOND DUST"],"transNames":["钻石星尘"]}
    ]}}"""), NeteaseExploreSearchType.ARTIST).items.filterIsInstance<ExploreSearchResult.Artist>().map { it.result }

    @Test fun workQueryRestoresCreditedBandSongsAheadOfLiteralCoversAndResourcePromotions() {
        val raw = songs()
        val baseline = ExploreSearchRankingSession(query).rank(raw)
        assertTrue((baseline.first() as ExploreSearchResult.Song).song.name.contains(query))
        val intent = inferSearchArtistIntent(query, searchSongItems(raw), artists())!!
        val session = ExploreSearchRankingSession(query).apply { artistIntent = intent }
        val promo = ExploreSearchResult.Song(SongItem(12, "4K修复【哭泣少女乐队】13集全（网盘资源分享）", "Uploader", "bilibili", 0, 93_000, null, channelId = "bilibili"))
        val liveBase = (raw.first { it is ExploreSearchResult.Song && it.song.neteaseArtists.orEmpty().any { artist -> artist.id == 57020536L } } as ExploreSearchResult.Song).song
        val live = ExploreSearchResult.Song(liveBase.copy(id = 99, audioId = "99", name = "雑踏、僕らの街（哭泣少女乐队 Live）", album = "Live"))
        val ranked = session.rank(listOf(promo, live) + baseline)
        assertEquals(2, ranked.takeWhile { it is ExploreSearchResult.Artist }.size)
        val firstSongs = searchSongItems(ranked).take(6)
        assertTrue(firstSongs.all { song -> song.neteaseArtists.orEmpty().any { it.id in setOf(57020536L, 62046853L) } })
        assertTrue(firstSongs.none { it.id == 99L })
        assertEquals(promo, ranked.last())
        assertTrue(artists().first().aliases.contains("无刺有刺"))
        // Later source snapshots need not carry cards; the ranking session restores them without duplicates.
        assertEquals(ranked, session.rank(listOf(promo, live) + baseline))
    }

    @Test fun exactAliasesWorkWhileSongTitleAmbiguityWeakConsensusAndCoverRequestsDoNotInventIntent() {
        val raw = searchSongItems(songs())
        val alias = inferSearchArtistIntent("无刺有刺", raw, artists())!!
        assertEquals(listOf(57020536L), alias.artists.map { it.artist.id })
        assertNull(inferSearchArtistIntent(query + " 翻唱", raw, artists()))
        assertNull(inferSearchArtistIntent(query, raw.take(3), artists()))
        val unrelated = raw.map { it.copy(neteaseArtists = listOf(NeteaseArtistSummary(7, "Other"))) }
        assertNull(inferSearchArtistIntent(query, unrelated, artists()))
        val literal = raw.first().copy(name = "晴天")
        assertNull(inferSearchArtistIntent("晴天", listOf(literal) + raw, listOf(artists().first().copy(artist = NeteaseArtistSummary(9, "晴天")))))
        val duplicate = artists().map { it.copy(aliases = it.aliases + "Shared alias") }
        assertNull(inferSearchArtistIntent("Shared alias", raw, duplicate))
    }

    @Test fun intentArrivingDuringBackgroundRankingIsAppliedBeforePublicationAndOldQueriesStayCancelled() = runTest {
        val raw = songs()
        val session = ExploreSearchRankingSession(query)
        var current = true
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            session.rankWithCurrentChecks(raw, { emptyMap() }, { current }, StandardTestDispatcher(testScheduler))
        }
        session.artistIntent = inferSearchArtistIntent(query, searchSongItems(raw), artists())
        val ranked = result.await()!!
        assertTrue(ranked.first() is ExploreSearchResult.Artist)
        current = false
        assertNull(session.rankWithCurrentChecks(raw, { emptyMap() }, { current }, StandardTestDispatcher(testScheduler)))
    }
}
