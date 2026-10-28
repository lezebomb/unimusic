package moe.ouom.neriplayer.ui.viewmodel.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseSearchSongParserTest {

    @Test fun parsedVersionAliasesRemainVisibleToAggregateRankingAndPlaybackMetadata() {
        val raw = """{"code":200,"result":{"songCount":4,"songs":[
            {"id":1,"name":"Dream","dt":180000,"ar":[{"id":8,"name":"Artist"}],"al":{"name":"Album"},"alia":["Live"]},
            {"id":2,"name":"Dream","dt":180000,"ar":[{"id":8,"name":"Artist"}],"al":{"name":"Album"},"alias":["Cover"]},
            {"id":3,"name":"Dream","dt":180000,"ar":[{"id":8,"name":"Artist"}],"al":{"name":"Album"},"tns":["AI generated vocals"]},
            {"id":4,"name":"Dream","dt":180000,"ar":[{"id":8,"name":"Artist"}],"al":{"name":"Album"}}]}}"""
        val parsed = parseNeteaseSearchResults(raw, NeteaseExploreSearchType.SONG)
        assertEquals(4, parsed.totalCount)
        val ranked = ExploreSearchRankingSession("Dream").rank(parsed.items)
        assertEquals(4L, (ranked.first() as ExploreSearchResult.Song).song.id)
        val live = ExploreSearchRankingSession("Dream Live").rank(parsed.items).first() as ExploreSearchResult.Song
        assertEquals(1L, live.song.id)
        val expected = moe.ouom.neriplayer.core.player.policy.source.RecordingMetadata("Dream", "Artist", 180000)
        for (item in parsed.items.take(3)) {
            val song = (item as ExploreSearchResult.Song).song
            assertTrue(moe.ouom.neriplayer.core.player.policy.source.RecordingMatchPolicy.hasVersionConflict(
                expected, expected.copy(title = song.name)))
        }
    }

    @Test fun sharedSongRootKeepsSourceMetadataCountsAndValidOrderWithoutMutation() {
        val root = org.json.JSONObject("""{"code":200,"result":{"songCount":32,"songs":[
            {"id":7,"name":"Dream","dt":180000,"ar":[{"id":8,"name":"Artist"}],"al":{"id":9,"name":"Album"}},
            {"id":0,"name":"invalid"},{"id":6,"name":"Other"}]}}""")
        val before = root.toString()
        val parsed = parseNeteaseSearchResults(root, NeteaseExploreSearchType.SONG)
        val songs = parsed.items.map { (it as ExploreSearchResult.Song).song }
        assertEquals(listOf(7L, 6L), songs.map { it.id })
        assertEquals(32, parsed.totalCount)
        assertEquals("netease", songs.first().channelId)
        assertEquals("7", songs.first().audioId)
        assertEquals("Artist", songs.first().artist)
        assertEquals(180000L, songs.first().durationMs)
        assertEquals(before, root.toString())
    }

    @Test
    fun `playlist search parser returns summaries and total count`() {
        val raw = """
            {
              "code": 200,
              "result": {
                "playlistCount": 31,
                "playlists": [
                  {
                    "id": 44,
                    "name": "Demo Playlist",
                    "coverImgUrl": "http://example.test/playlist.jpg",
                    "playCount": 12345,
                    "trackCount": 20
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = parseNeteaseSearchResults(raw, NeteaseExploreSearchType.PLAYLIST)
        val playlist = (parsed.items.single() as ExploreSearchResult.Playlist).playlist

        assertEquals(31, parsed.totalCount)
        assertEquals(44L, playlist.id)
        assertEquals("Demo Playlist", playlist.name)
        assertEquals("https://example.test/playlist.jpg", playlist.picUrl)
        assertEquals(12345L, playlist.playCount)
        assertEquals(20, playlist.trackCount)
    }

    @Test
    fun `artist search parser returns artist metadata and total count`() {
        val raw = """
            {
              "code": 200,
              "result": {
                "artistCount": 9,
                "artists": [
                  {
                    "id": 55,
                    "name": "Demo Artist",
                    "picUrl": "http://example.test/artist.jpg",
                    "musicSize": 18,
                    "albumSize": 4
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = parseNeteaseSearchResults(raw, NeteaseExploreSearchType.ARTIST)
        val artist = (parsed.items.single() as ExploreSearchResult.Artist).result

        assertEquals(9, parsed.totalCount)
        assertEquals(55L, artist.artist.id)
        assertEquals("Demo Artist", artist.artist.name)
        assertEquals("https://example.test/artist.jpg", artist.picUrl)
        assertEquals(18, artist.musicSize)
        assertEquals(4, artist.albumSize)
    }

    @Test
    fun `non success response returns no search results`() {
        val parsed = parseNeteaseSearchResults(
            raw = """{"code":500}""",
            type = NeteaseExploreSearchType.SONG
        )

        assertTrue(parsed.items.isEmpty())
        assertEquals(null, parsed.totalCount)
    }
}
