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
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.streamOnlyPlaybackUrl
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchFilter
import moe.ouom.neriplayer.platform.gdstudio.api.GdStudioClient
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual platform transport checks. A selected public sample is not an original-recording label. */
@RunWith(AndroidJUnit4::class)
class OtherSourceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("unimusicLiveOtherSources") == "true")

    @Test fun bilibiliSampleDecodes() = runBlocking {
        enabled()
        val start = SystemClock.elapsedRealtime()
        val video = withTimeout(8_000) { AppContainer.biliClient.searchVideos("Canon").items.first { it.aid > 0 } }
        val info = withTimeout(4_000) { AppContainer.biliClient.getVideoBasicInfoByAvid(video.aid) }
        assertTrue(info.aid == video.aid)
        val page = info.pages.first { it.cid > 0 }
        val song = SongItem(info.aid, info.title, info.ownerName, "bilibili", 0,
            page.durationSec * 1000L, null, channelId = "bilibili", audioId = info.aid.toString(), subAudioId = page.cid.toString())
        play("bilibili", song, SystemClock.elapsedRealtime() - start)
    }

    @Test fun youtubeMusicSampleDecodes() = runBlocking {
        enabled()
        val start = SystemClock.elapsedRealtime()
        val track = withTimeout(8_000) { AppContainer.youtubeMusicClient.search("Canon", limit = 3,
            filter = YouTubeMusicSearchFilter.Song).first { it.videoId.isNotBlank() } }
        val song = SongItem(stableYouTubeMusicId(track.videoId), track.title, track.artist, track.album, 0,
            track.durationMs, track.coverUrl, mediaUri = buildYouTubeMusicMediaUri(track.videoId),
            channelId = "youtubeMusic", audioId = track.videoId)
        play("youtube_music", song, SystemClock.elapsedRealtime() - start)
    }

    @Test fun gdPublicClientAddressDecodes() = runBlocking {
        enabled()
        val start = SystemClock.elapsedRealtime()
        val id = 28815250L
        val raw = withTimeout(4_000) { AppContainer.neteaseClient.getSongDetailCancellable(listOf(id)) }
        val metadata = JSONObject(raw).getJSONArray("songs").getJSONObject(0)
        assertTrue(metadata.getLong("id") == id)
        val artists = metadata.getJSONArray("ar")
        val artist = (0 until artists.length()).joinToString("/") { artists.getJSONObject(it).getString("name") }
        val audio = requireNotNull(GdStudioClient().resolveNetease(id.toString())) { "GD returned no eligible audio address" }
        val song = SongItem(id, metadata.getString("name"), artist, metadata.getJSONObject("al").getString("name"),
            0, metadata.getLong("dt"), null, channelId = "netease", audioId = id.toString(),
            streamUrl = streamOnlyPlaybackUrl(audio.url))
        play("gdstudio_netease", song, SystemClock.elapsedRealtime() - start)
    }

    private suspend fun play(provider: String, song: SongItem, discoveryMs: Long) {
        val context = instrumentation.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val started = SystemClock.elapsedRealtime()
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(volume, 3), 0)
            instrumentation.runOnMainSync {
                PlayerManager.initialize(context.applicationContext as Application)
                PlayerManager.pause()
                PlayerManager.playPlaylist(listOf(song), 0)
            }
            withTimeout(20_000) {
                while (PlayerManager.currentSongFlow.value?.audioId != song.audioId ||
                    !PlayerManager.isPlayingFlow.value || PlayerManager.playbackPositionFlow.value < 1_500) delay(100)
            }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("source", provider); putString("outcome", "PLAYED")
                putLong("discovery_ms", discoveryMs)
                putLong("play_request_to_observed_position_ms", SystemClock.elapsedRealtime() - started)
                putLong("observed_position_ms", PlayerManager.playbackPositionFlow.value)
            })
        } catch (failure: Exception) {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("source", provider); putString("outcome", "FAILED")
                putString("failure_type", failure.javaClass.simpleName)
            })
            throw AssertionError("$provider playback failed (${failure.javaClass.simpleName})")
        } finally {
            instrumentation.runOnMainSync { PlayerManager.pause() }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
