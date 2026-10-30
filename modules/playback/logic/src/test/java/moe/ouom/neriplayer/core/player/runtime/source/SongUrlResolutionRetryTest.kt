package moe.ouom.neriplayer.core.player.runtime.source

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.PlaybackUrlCandidate
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SongUrlResolutionRetryTest {
    @Test
    fun `resolution retries five times before succeeding`() = runBlocking {
        var attempts = 0
        val retryNumbers = mutableListOf<Int>()

        val result = retrySongUrlResolution(
            delayBeforeRetry = { retryNumbers += it }
        ) { attempt ->
            attempts += 1
            if (attempt == SONG_URL_RESOLUTION_RETRY_COUNT) {
                SongUrlResult.Success("https://example.com/audio.m4a")
            } else {
                SongUrlResult.Failure
            }
        }

        assertEquals(SONG_URL_RESOLUTION_RETRY_COUNT + 1, attempts)
        assertEquals((1..SONG_URL_RESOLUTION_RETRY_COUNT).toList(), retryNumbers)
        assertEquals("https://example.com/audio.m4a", (result as SongUrlResult.Success).url)
    }

    @Test
    fun `resolution does not retry a login requirement`() = runBlocking {
        var attempts = 0

        val result = retrySongUrlResolution(
            delayBeforeRetry = { error("login result must not retry") }
        ) {
            attempts += 1
            SongUrlResult.RequiresLogin
        }

        assertEquals(1, attempts)
        assertSame(SongUrlResult.RequiresLogin, result)
    }

    @Test
    fun `authoritative stream wait skips local resolution`() = runBlocking {
        var resolved = false

        val result = resolveSongUrlOrWaitForAuthoritativeStream(
            shouldWaitForAuthoritativeStream = { true }
        ) {
            resolved = true
            SongUrlResult.Failure
        }

        assertSame(SongUrlResult.WaitingForAuthoritativeStream, result)
        assertEquals(false, resolved)
    }

    @Test
    fun `authoritative stream wait is rechecked after local resolution`() = runBlocking {
        var shouldWait = false

        val result = resolveSongUrlOrWaitForAuthoritativeStream(
            shouldWaitForAuthoritativeStream = { shouldWait }
        ) {
            shouldWait = true
            SongUrlResult.Success(
                url = "https://example.com/preview.m4a",
                isPreviewClip = true,
                noticeMessage = "preview"
            )
        }

        assertSame(SongUrlResult.WaitingForAuthoritativeStream, result)
    }
    @Test
    fun `full recording keeps provenance and removes preview backups`() {
        val fullBackup = PlaybackUrlCandidate("https://example.com/full-backup.mp3", cacheKeyOverride = "full-backup")
        val result = SongUrlResult.Success(
            url = "https://example.com/full.mp3", durationMs = 180_000, cacheKeyOverride = "full-primary",
            audioInfo = PlaybackAudioInfo(PlaybackAudioSource.NETEASE),
            fallbackCandidates = listOf(
                PlaybackUrlCandidate("https://example.com/preview.mp3", isPreviewClip = true),
                fullBackup,
                PlaybackUrlCandidate("https://example.com/legacy-preview.mp3",
                    audioInfo = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, isPreviewClip = true))
            )
        ).fullRecordingPlaybackResultOrNull()!!

        assertEquals("https://example.com/full.mp3", result.url)
        assertEquals(180_000L, result.durationMs)
        assertEquals("full-primary", result.cacheKeyOverride)
        assertEquals(listOf(fullBackup), result.fallbackCandidates)
    }

    @Test
    fun `audio metadata preview blocks an unmarked legacy result`() {
        val legacy = SongUrlResult.Success("https://example.com/legacy.mp3",
            audioInfo = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, isPreviewClip = true))
        assertNull(legacy.fullRecordingPlaybackResultOrNull())
    }

    @Test
    fun `a full backup cannot make the preview primary playable`() {
        val preview = SongUrlResult.Success("https://example.com/preview.mp3", isPreviewClip = true,
            fallbackCandidates = listOf(PlaybackUrlCandidate("https://example.com/full.mp3")))
        assertNull(preview.fullRecordingPlaybackResultOrNull())
    }

}
