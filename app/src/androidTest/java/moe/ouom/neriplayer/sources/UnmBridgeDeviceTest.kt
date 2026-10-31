package moe.ouom.neriplayer.sources

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.settings.SettingsRepository
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device closure check for the self-hosted matching bridge.
 *
 * Requires a running bridge reachable from the device (`adb reverse tcp:8900 tcp:8900` works) and
 * the instrumentation arg `unimusicLiveUnmBridge=true`. Playback is the only accepted evidence:
 * a resolved address alone is not a complete recording.
 */
@RunWith(AndroidJUnit4::class)
class UnmBridgeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val bridgeUrl get() = InstrumentationRegistry.getArguments()
        .getString("unmBridgeUrl") ?: "http://127.0.0.1:8900"
    private var repository: SettingsRepository? = null

    private fun enabled() = assumeTrue(
        InstrumentationRegistry.getArguments().getString("unimusicLiveUnmBridge") == "true"
    )

    /** Representative tracks whose Netease entries only expose preview clips for this account. */
    private val samples = listOf(
        Triple(2142857204L, "雑踏、僕らの街", 184243L),
        Triple(2145269432L, "誰にもなれない私だから", 189816L),
        Triple(2142858141L, "空の箱", 184510L),
        Triple(2683843127L, "ダレモ", 186566L)
    )

    @Before
    fun enableBridge() = runBlocking {
        enabled()
        val settings = SettingsRepository(instrumentation.targetContext.applicationContext)
        repository = settings
        settings.setUnmBridgeBaseUrl(bridgeUrl)
        settings.setUnmBridgeEnabled(true)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("diagnostic", "UNM_BRIDGE_CONFIGURED")
            putString("bridge_url", bridgeUrl)
        })
    }

    @After
    fun disableBridge() = runBlocking {
        val settings = repository ?: return@runBlocking
        settings.setUnmBridgeEnabled(false)
        settings.setUnmBridgeBaseUrl("")
    }

    @Test
    fun representativeTracksResolveFullRecordings() = runBlocking {
        enabled()
        val artist = "トゲナシトゲアリ"
        for ((id, title, durationMs) in samples) {
            val song = SongItem(id, title, artist, "", 0, durationMs, null,
                channelId = "netease", audioId = id.toString())
            play(song)
        }
    }

    private suspend fun play(song: SongItem) = coroutineScope {
        val context = instrumentation.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val started = SystemClock.elapsedRealtime()
        val previousUrl = PlayerManager.currentMediaUrlFlow.value
        var errorKind = "NONE"
        val events = launch(start = CoroutineStart.UNDISPATCHED) {
            PlayerManager.playerEventFlow.collect { event ->
                errorKind = when (event) {
                    is PlayerEvent.ShowLoginPrompt -> "LOGIN_REQUIRED"
                    is PlayerEvent.ShowError -> "PLAYBACK_ERROR"
                    else -> errorKind
                }
            }
        }
        try {
            // Silent run: the stream volume must stay at zero so device checks never make noise.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            instrumentation.runOnMainSync {
                PlayerManager.initialize(context.applicationContext as android.app.Application)
                PlayerManager.pause()
                PlayerManager.playPlaylist(listOf(song), 0)
            }
            withTimeout(25_000) {
                while (PlayerManager.currentSongFlow.value?.audioId != song.audioId ||
                    !PlayerManager.isPlayingFlow.value || PlayerManager.playbackPositionFlow.value < 2_000
                ) {
                    delay(100)
                }
            }
            val mediaUrl = PlayerManager.currentMediaUrlFlow.value.orEmpty()
            val host = runCatching { java.net.URI(mediaUrl).host.orEmpty() }.getOrDefault("")
            instrumentation.sendStatus(2, Bundle().apply {
                putString("source", "unm_netease")
                putString("outcome", "PLAYED")
                putString("song", song.name)
                putString("media_host", host)
                putLong("observed_position_ms", PlayerManager.playbackPositionFlow.value)
                putLong("elapsed_ms", SystemClock.elapsedRealtime() - started)
                putBoolean("address_changed", mediaUrl != previousUrl)
            })
            assertTrue("No full recording host observed for ${song.name}", host.isNotEmpty())
        } catch (failure: Exception) {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("source", "unm_netease")
                putString("outcome", "FAILED")
                putString("song", song.name)
                putString("song_id", song.id.toString())
                putString("failure_type", failure.javaClass.simpleName)
                putString("player_error_kind", errorKind)
                putString("media_url_head", PlayerManager.currentMediaUrlFlow.value.orEmpty().take(48))
            })
            throw AssertionError("${song.name} did not play (${failure.javaClass.simpleName})")
        } finally {
            events.cancel()
            instrumentation.runOnMainSync { PlayerManager.pause() }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
