package moe.ouom.neriplayer.platform.netease.api.playback.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePlaybackResponseParserTest {

    @Test
    fun parsePlayback_requiresTheRequestedNativeIdentityWhenProvided() {
        for (id in listOf("43", "42.5", "null")) {
            val raw = """{"code":200,"data":[{"id":$id,"url":"https://example.com/audio.mp3","time":180000}]}"""
            assertTrue(NeteasePlaybackResponseParser.parsePlayback(raw, 180_000L, 42L)
                is NeteasePlaybackResponseParser.PlaybackResult.Failure)
        }
        val missing = """{"code":200,"data":[{"url":"https://example.com/audio.mp3","time":180000}]}"""
        assertTrue(NeteasePlaybackResponseParser.parsePlayback(missing, 180_000L, 42L)
            is NeteasePlaybackResponseParser.PlaybackResult.Failure)
        // Context-free metadata consumers retain the old two-argument API.
        assertTrue(NeteasePlaybackResponseParser.parsePlayback(missing, 180_000L)
            is NeteasePlaybackResponseParser.PlaybackResult.Success)
        val matching = """{"code":200,"data":[{"id":42,"url":"https://example.com/audio.mp3","time":180000}]}"""
        assertEquals(42L, (NeteasePlaybackResponseParser.parsePlayback(matching, 180_000L, 42L)
            as NeteasePlaybackResponseParser.PlaybackResult.Success).songId)
    }

    @Test
    fun parsePlayback_marksUnlabelledTruncatedResponseAsPreview() {
        val raw = """{"code":200,"data":[{"url":"https://example.com/audio.mp3","time":60000}]}"""
        val result = NeteasePlaybackResponseParser.parsePlayback(raw, 184_243L)
            as NeteasePlaybackResponseParser.PlaybackResult.Success
        assertEquals(NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP, result.notice)
    }

    @Test
    fun parsePlayback_keepsShortFullSongsAndSmallDurationDifferences() {
        for ((expected, reported) in listOf(30_000L to 30_000L, 180_000L to 176_000L, 0L to 60_000L)) {
            val raw = """{"code":200,"data":[{"url":"https://example.com/audio.mp3","time":$reported}]}"""
            val result = NeteasePlaybackResponseParser.parsePlayback(raw, expected)
                as NeteasePlaybackResponseParser.PlaybackResult.Success
            assertNull(result.notice)
        }
        val explicit = """{"code":200,"data":[{"url":"https://example.com/audio.mp3","freeTrialInfo":{}}]}"""
        assertEquals(NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP,
            (NeteasePlaybackResponseParser.parsePlayback(explicit, -1L)
                as NeteasePlaybackResponseParser.PlaybackResult.Success).notice)
    }

    @Test
    fun parsePlayback_marksNoPermissionWhenUrlIsJsonNull() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": null,
                "code": 404,
                "fee": 0,
                "freeTrialPrivilege": {
                  "cannotListenReason": 1
                }
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(raw, originalDurationMs = 180_000L)

        assertTrue(result is NeteasePlaybackResponseParser.PlaybackResult.Failure)
        assertEquals(
            NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION,
            (result as NeteasePlaybackResponseParser.PlaybackResult.Failure).reason
        )
    }

    @Test
    fun parsePlayback_marksPreviewWhenOnlyClipIsPlayable() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": "http://m801.music.126.net/demo.mp3",
                "type": "mp3",
                "time": 30040,
                "freeTrialInfo": {
                  "fragmentType": 6
                }
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(raw, originalDurationMs = 171_111L)

        assertTrue(result is NeteasePlaybackResponseParser.PlaybackResult.Success)
        assertEquals(
            NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP,
            (result as NeteasePlaybackResponseParser.PlaybackResult.Success).notice
        )
    }

    @Test
    fun parsePlayback_marksPreviewWhenFreeTrialInfoExistsEvenForShortSong() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": "http://m801.music.126.net/demo.mp3",
                "type": "mp3",
                "time": 30040,
                "freeTrialInfo": {
                  "fragmentType": 6
                }
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(raw, originalDurationMs = 30_000L)

        assertTrue(result is NeteasePlaybackResponseParser.PlaybackResult.Success)
        assertEquals(
            NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP,
            (result as NeteasePlaybackResponseParser.PlaybackResult.Success).notice
        )
    }

    @Test
    fun parsePlayback_marksPreviewWhenPlayableClipIsTwentySomethingSeconds() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": "http://m702.music.126.net/demo.mp3",
                "type": "MP3",
                "time": 26984,
                "freeTrialInfo": {
                  "fragmentType": 1017,
                  "start": 1,
                  "end": 28
                }
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(raw, originalDurationMs = 180_000L)

        assertTrue(result is NeteasePlaybackResponseParser.PlaybackResult.Success)
        assertEquals(
            NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP,
            (result as NeteasePlaybackResponseParser.PlaybackResult.Success).notice
        )
    }

    @Test
    fun parseDownloadInfo_rejectsLiteralNullUrl() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": "null",
                "type": "mp3"
              }]
            }
        """.trimIndent()

        assertNull(NeteasePlaybackResponseParser.parseDownloadInfo(raw))
    }

    @Test
    fun parseDownloadInfo_readsServerReportedSize() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": "https://m801.music.126.net/demo.mp3",
                "type": "mp3",
                "size": 3758751
              }]
            }
        """.trimIndent()

        assertEquals(
            3_758_751L,
            NeteasePlaybackResponseParser.parseDownloadInfo(raw)?.contentLength
        )
    }

    @Test
    fun parsePlayback_treatsCode404AsRestrictedResource() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": null,
                "code": 404,
                "fee": 0
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(raw, originalDurationMs = 240_000L)

        assertTrue(result is NeteasePlaybackResponseParser.PlaybackResult.Failure)
        assertEquals(
            NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION,
            (result as NeteasePlaybackResponseParser.PlaybackResult.Failure).reason
        )
    }

    @Test
    fun parsePlayback_readsServerReportedLevelAndBitrate() {
        val raw = """
            {
              "code": 200,
              "data": [{
                "url": "https://m701.music.126.net/full.mp3",
                "type": "mp3",
                "level": "exhigh",
                "br": 320000,
                "size": 7200000
              }]
            }
        """.trimIndent()

        val result = NeteasePlaybackResponseParser.parsePlayback(
            raw,
            originalDurationMs = 180_000L
        ) as NeteasePlaybackResponseParser.PlaybackResult.Success

        assertEquals("exhigh", result.level)
        assertEquals(320, result.bitrateKbps)
        assertEquals(7_200_000L, result.contentLength)
    }
}
