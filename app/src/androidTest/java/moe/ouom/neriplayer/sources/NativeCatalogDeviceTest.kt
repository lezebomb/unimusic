package moe.ouom.neriplayer.sources

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.unified.CatalogTrack
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.SourceCheckStatus
import moe.ouom.neriplayer.data.unified.catalogPlaybackSong
import moe.ouom.neriplayer.platform.kuwo.api.KuwoAudioClient
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicAudioClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opt in with -e unimusicLiveSources true. Never opens an account or resets app data. */
@RunWith(AndroidJUnit4::class)
class NativeCatalogDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("unimusicLiveSources") == "true")

    @Test fun kuwoNormalWebSourceDecodesThroughProductionPlayer() = runBlocking {
        enabled()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val started = SystemClock.elapsedRealtime()
        val response = KuwoAudioClient().resolve("328578424")
        assertEquals(SourceCheckStatus.ADDRESS_RESOLVED, response.status)
        val song = catalogPlaybackSong(CatalogTrack(SongSourceKey("kuwo", "328578424"), response.metadata!!, null))!!
        val addressMs = SystemClock.elapsedRealtime() - started
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(previousVolume, 3), 0)
            val playStarted = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                PlayerManager.initialize(context.applicationContext as Application)
                PlayerManager.playPlaylist(listOf(song), 0)
            }
            withTimeout(25_000) {
                while (!PlayerManager.isPlayingFlow.value || PlayerManager.playbackPositionFlow.value < 1_500) delay(100)
            }
            val position = PlayerManager.playbackPositionFlow.value
            assertTrue(position >= 1_500)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("unimusic_source", "kuwo")
                putLong("address_ms", addressMs)
                putLong("play_request_to_observed_1500ms_position_ms", SystemClock.elapsedRealtime() - playStarted)
                putLong("observed_position_ms", position)
            })
        } finally {
            instrumentation.runOnMainSync { PlayerManager.pause() }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
        }
    }

    @Test fun qqAnonymousPermissionDenialIsNotReportedAsPlayable() = runBlocking {
        enabled()
        val result = QQMusicAudioClient().resolve("003OUlho2HcRHC", null)
        assertEquals(SourceCheckStatus.AUTH_REQUIRED, result.status)
        assertNull(result.url)
    }
}
