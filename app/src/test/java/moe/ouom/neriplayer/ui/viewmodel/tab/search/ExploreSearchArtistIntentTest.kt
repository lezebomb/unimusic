package moe.ouom.neriplayer.ui.viewmodel.tab.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    @Test fun optionalArtistLookupOverlapsSongsAndReusesItsSingleResult() = runTest {
        var calls = 0
        val lookup = launchOptionalSearchLookup(1_200, { true }) { calls++; delay(600); "artist" }
        val songs = async { delay(800); "songs" }
        assertEquals("songs", songs.await())
        assertEquals("artist", lookup.await())
        assertEquals("artist", lookup.await())
        assertEquals(800L, currentTime)
        assertEquals(1, calls)
    }

    @Test fun optionalArtistTimeoutAndProviderFailuresKeepPrimarySongsAvailable() = runTest {
        var released = false
        val lookup = launchOptionalSearchLookup(1_200, { true }) {
            try { delay(5_000); "late" } finally { released = true }
        }
        val songs = async { delay(800); "songs" }
        assertEquals("songs", songs.await())
        assertEquals(800L, currentTime)
        assertNull(lookup.await())
        assertEquals(1_200L, currentTime)
        assertTrue(released)
        assertNull(launchOptionalSearchLookup<String>(1_200, { true }) { error("unavailable") }.await())
        assertNull(launchOptionalSearchLookup<String>(1_200, { true }) { throw CancellationException("provider cancelled") }.await())
    }

    @Test fun optionalArtistLookupRejectsOldContextAndPropagatesActualCancellation() = runTest {
        var current = false
        var calls = 0
        assertNull(launchOptionalSearchLookup(1_200, { current }) { calls++; "unused" }.await())
        assertEquals(0, calls)
        current = true
        val stale = launchOptionalSearchLookup(1_200, { current }) { delay(100); "old account" }
        runCurrent()
        current = false
        assertNull(stale.await())
        var released = false
        val cancelled = launchOptionalSearchLookup(1_200, { true }) {
            try { delay(500); "late" } finally { released = true }
        }
        runCurrent()
        cancelled.cancel()
        try { cancelled.await(); fail("lookup cancellation must propagate") } catch (_: CancellationException) { }
        assertTrue(released)
    }

    @Test fun verifiedCatalogDjVariantCannotOutrankAnArtistsUnmodifiedRecording() {
        val artist = NeteaseArtistSummary(1, "Adele")
        val entity = NeteaseSearchArtistResult(artist, null, 1, 1)
        val studio = ExploreSearchResult.Song(SongItem(2, "Easy On Me", "Adele", "30", 0, 224_000, null,
            channelId = "netease", audioId = "2", neteaseArtists = listOf(artist)))
        val remix = ExploreSearchResult.Catalog(moe.ouom.neriplayer.data.model.unified.CatalogTrack(
            moe.ouom.neriplayer.data.model.unified.SongSourceKey("kuwo", "3"),
            moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata("Hello (Teddy Cream x Louis Quinn Booty) 1", "Adele", "Hello", 258_000), null))
        val mashup = remix.copy(track = remix.track.copy(source = remix.track.source.copy(resourceId = "4"),
            metadata = remix.track.metadata.copy(title = "Rolling In the Deep (Mash Up版)")))
        val unknownMix = remix.copy(track = remix.track.copy(source = remix.track.source.copy(resourceId = "5"),
            metadata = remix.track.metadata.copy(title = "Easy On Me (Producer Bounce Mix)", durationMs = studio.song.durationMs)))
        val sameRecording = remix.copy(track = remix.track.copy(source = remix.track.source.copy(resourceId = "6"),
            metadata = remix.track.metadata.copy(title = studio.song.name, album = studio.song.album, durationMs = studio.song.durationMs)))
        val ranker = ExploreSearchRankingSession("Adele").apply {
            artistIntent = inferSearchArtistIntent("Adele", listOf(studio.song), listOf(entity))
        }
        val checks = listOf(remix, mashup, unknownMix, sameRecording).associate { it.track.source to moe.ouom.neriplayer.data.model.unified.SourceCheckResult(
            moe.ouom.neriplayer.data.model.unified.SourceCheckStatus.ADDRESS_RESOLVED, System.currentTimeMillis()) }
        val songs = ranker.rank(listOf(remix, mashup, unknownMix, studio, sameRecording), checks).filterNot { it is ExploreSearchResult.Artist }
        assertEquals(listOf(sameRecording, studio), songs.take(2))
        assertEquals(5, songs.size)
    }

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
