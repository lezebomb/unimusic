package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.RecordingMismatch
import moe.ouom.neriplayer.data.model.unified.RecordingVersion
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResult
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResultType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceChoiceMetadataTest {
    @Test
    fun `netease preserves native identity and alias version evidence without accepting malformed ids`() {
        val raw = """{"code":200,"result":{"songs":[
            {"id":77,"name":"Song","ar":[{"id":9,"name":"Artist"}],"al":{"id":3,"name":"Album"},"dt":180000,"alia":["Live"]},
            {"id":1.5,"name":"Song","ar":[{"id":9,"name":"Artist"}],"dt":180000},
            {"id":0,"name":"Song","dt":180000}
        ]}}"""
        val candidate = parseSourceChoiceNetease(raw).single()

        assertEquals(SongSourceKey("netease", "77"), candidate.key)
        assertEquals("Artist", candidate.metadata.artist)
        assertEquals(180_000L, candidate.metadata.durationMs)
        assertEquals(RecordingVersion.LIVE, candidate.metadata.version)
        assertTrue(candidate.metadata.album.contains("Live"))
        assertEquals(RecordingMismatch.VERSION, assessSourceChoice(subject(), candidate).reason)
        assertThrows(SourceChoiceAuthRequiredException::class.java) {
            parseSourceChoiceNetease("""{"code":301}""")
        }
    }

    @Test
    fun `youtube uses only a video id and real metadata without filling missing artist or duration`() {
        val result = YouTubeMusicSearchResult("abcdefghijk", "Song", "", "Album", "", "", "", 0L,
            YouTubeMusicSearchResultType.Song)
        val candidate = requireNotNull(sourceChoiceYouTube(result))

        assertEquals(SongSourceKey("youtube_music", "abcdefghijk"), candidate.key)
        assertEquals("", candidate.metadata.artist)
        assertEquals(0L, candidate.metadata.durationMs)
        assertFalse(assessSourceChoice(subject(), candidate).accepted)
        assertNull(sourceChoiceYouTube(result.copy(videoId = "https://youtube.com/watch?v=abcdefghijk")))
        val live = requireNotNull(sourceChoiceYouTube(result.copy(artist = "Artist", durationMs = 180_000L,
            subtitle = "Live")))
        assertEquals(RecordingMismatch.VERSION, assessSourceChoice(subject(), live).reason)
    }

    @Test
    fun `session changes for account and youtube gate changes but ignores ordinary cookie rotation`() {
        val bili = mapOf("DedeUserID" to "42", "SESSDATA" to "bili-secret")
        val netease = mapOf("MUSIC_U" to "netease-secret")
        val youtube = YouTubeAuthBundle(cookies = mapOf("SID" to "youtube-secret", "SAPISID" to "sapi-secret"),
            xGoogAuthUser = "0")
        val session = sourceChoiceSession(bili, netease, youtube, true)

        assertNotEquals(session, sourceChoiceSession(bili, mapOf("MUSIC_U" to "other-account"), youtube, true))
        assertNotEquals(session, sourceChoiceSession(bili, netease, youtube.copy(xGoogAuthUser = "1"), true))
        assertNotEquals(session, sourceChoiceSession(bili, netease, youtube, false))
        assertEquals(session, sourceChoiceSession(bili, netease + ("__csrf" to "rotated"),
            youtube.copy(cookies = youtube.cookies + ("VISITOR_INFO1_LIVE" to "rotated"), savedAt = 99L), true))
        assertFalse(session.toString().contains("secret"))
    }

    private fun subject() = SongItem(7L, "Song", "Artist", "Album", 3L, 180_000L, null,
        channelId = "netease", audioId = "7")
}
