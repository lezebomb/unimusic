package moe.ouom.neriplayer.sources

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import java.util.Collections
import moe.ouom.neriplayer.platform.youtube.repository.YouTubeMusicPlaybackRepository
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.platform.youtube.api.auth.hasLoginCookies
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.streamOnlyPlaybackUrl
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchFilter
import moe.ouom.neriplayer.platform.gdstudio.api.GdStudioClient
import moe.ouom.neriplayer.platform.youtube.api.transport.YOUTUBE_TEXT_RESPONSE_MAX_BYTES
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
        awaitSavedYouTubeAccountIfRequested()
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

    /** Mirrors the upstream warm-bootstrap -> shared resolver path; emits no request payloads. */
    @Test fun youtubeUpstreamWarmupDiagnostics() = runBlocking {
        enabled()
        awaitSavedYouTubeAccountIfRequested()
        val traces = Collections.synchronizedList(mutableListOf<String>())
        val client = AppContainer.sharedOkHttpClient.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val kind = when {
                request.url.encodedPath.startsWith("/youtubei/") -> "PLAYER"
                request.url.encodedPath.contains("/s/player/") -> "PLAYER_JS"
                request.url.encodedPath == "/" -> "BOOTSTRAP"
                else -> "OTHER"
            }
            val began = SystemClock.elapsedRealtime()
            try {
                val response = chain.proceed(request)
                val rawRoot = if (kind == "PLAYER") runCatching {
                    JSONObject(response.peekBody(YOUTUBE_TEXT_RESPONSE_MAX_BYTES).string())
                }.getOrNull() else null
                val root = rawRoot?.optJSONObject("playerResponse") ?: rawRoot
                val status = root?.optJSONObject("playabilityStatus")?.optString("status")
                    ?.takeIf { it in setOf("OK", "ERROR", "LOGIN_REQUIRED", "UNPLAYABLE", "LIVE_STREAM_OFFLINE", "CONTENT_CHECK_REQUIRED", "AGE_CHECK_REQUIRED") } ?: "NONE"
                val formats = root?.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")?.length() ?: 0
                val reason = root?.optJSONObject("playabilityStatus")?.optString("reason").orEmpty().lowercase()
                val reasonKind = when {
                    reason.contains("country") || reason.contains("地区") -> "REGION"
                    reason.contains("premium") || reason.contains("会员") -> "ENTITLEMENT"
                    reason.contains("bot") || reason.contains("验证") -> "VERIFICATION"
                    reason.contains("sign in") || reason.contains("登录") -> "SIGN_IN"
                    reason.contains("age") || reason.contains("年龄") -> "AGE"
                    reason.contains("unavailable") || reason.contains("不可用") -> "UNAVAILABLE"
                    else -> "UNSPECIFIED"
                }
                val cipherFormats = root?.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")?.let { list ->
                    (0 until list.length()).count { !list.optJSONObject(it)?.optString("signatureCipher").isNullOrBlank() }
                } ?: 0
                val clientId = request.header("X-YouTube-Client-Name").orEmpty().filter(Char::isDigit)
                traces += "$kind client=$clientId http=${response.code} parsed=${root != null} status=$status reason=$reasonKind formats=$formats cipher_formats=$cipherFormats ms=${SystemClock.elapsedRealtime() - began}"
                response
            } catch (failure: Exception) {
                traces += "$kind failure=${failure.javaClass.simpleName} ms=${SystemClock.elapsedRealtime() - began}"
                throw failure
            }
        }.build()
        val repository = YouTubeMusicPlaybackRepository(client,
            authProvider = AppContainer.youtubeAuthRepo::getAuthOnce,
            applicationContext = instrumentation.targetContext.applicationContext)
        val started = SystemClock.elapsedRealtime()
        var ready = false
        var accountProfileVerified = false
        try {
            if (InstrumentationRegistry.getArguments().getString("unimusicRequireYoutubeAccount") == "true") {
                accountProfileVerified = runCatching { withTimeout(8_000) {
                    AppContainer.youtubeAccountProfileClient.getAccountProfile(AppContainer.youtubeAuthRepo.getAuthOnce()) != null
                } }.getOrDefault(false)
            }
            val track = withTimeout(8_000) { AppContainer.youtubeMusicClient.search("Canon", limit = 3,
                filter = YouTubeMusicSearchFilter.Song).first { it.videoId.isNotBlank() } }
            withTimeout(12_000) { repository.warmBootstrap() }
            ready = withTimeout(25_000) { repository.getBestPlayableAudio(track.videoId) } != null
        } finally {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("diagnostic", "UPSTREAM_WARMUP_PATH")
                putBoolean("address_ready", ready)
                putBoolean("account_profile_verified", accountProfileVerified)
                putLong("elapsed_ms", SystemClock.elapsedRealtime() - started)
                putStringArrayList("http_trace", ArrayList(synchronized(traces) { traces.toList() }))
            })
        }
        assertTrue("Warmup path did not return an audio address; inspect redacted stage metrics", ready)
    }

    private suspend fun awaitSavedYouTubeAccountIfRequested() {
        if (InstrumentationRegistry.getArguments().getString("unimusicRequireYoutubeAccount") != "true") return
        withTimeout(10_000) { AppContainer.youtubeAuthRepo.authFlow.first { it.hasLoginCookies() } }
        instrumentation.sendStatus(2, Bundle().apply { putBoolean("saved_youtube_login_cookies_loaded", true) })
    }

    private suspend fun play(provider: String, song: SongItem, discoveryMs: Long) = coroutineScope {
        val context = instrumentation.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val started = SystemClock.elapsedRealtime()
        val previousUrl = PlayerManager.currentMediaUrlFlow.value
        var firstAddressMs = -1L
        var errorKind = "NONE"
        var firstErrorMs = -1L
        val phases = linkedSetOf<String>()
        val events = launch(start = CoroutineStart.UNDISPATCHED) {
            PlayerManager.playerEventFlow.collect { event ->
                if (event !is PlayerEvent.ShowNotice && firstErrorMs < 0) firstErrorMs = SystemClock.elapsedRealtime() - started
                errorKind = when (event) {
                    is PlayerEvent.ShowLoginPrompt -> "LOGIN_REQUIRED"
                    is PlayerEvent.ShowError -> when {
                        event.message.contains("403") -> "HTTP_403"
                        event.message.contains("429") -> "HTTP_429"
                        event.message.contains("disabled", ignoreCase = true) -> "DISABLED"
                        event.message.contains("超时") || event.message.contains("timed out", true) -> "TIMEOUT"
                        else -> "PLAYBACK_ERROR"
                    }
                    is PlayerEvent.ShowNotice -> errorKind
                }
            }
        }
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(volume, 3), 0)
            instrumentation.runOnMainSync {
                PlayerManager.initialize(context.applicationContext as Application)
                PlayerManager.pause()
                PlayerManager.playPlaylist(listOf(song), 0)
            }
            withTimeout(if (provider == "youtube_music") 35_000 else 20_000) {
                while (PlayerManager.currentSongFlow.value?.audioId != song.audioId ||
                    !PlayerManager.isPlayingFlow.value || PlayerManager.playbackPositionFlow.value < 1_500) {
                    if (firstAddressMs < 0 && PlayerManager.currentMediaUrlFlow.value?.let { it != previousUrl } == true) {
                        firstAddressMs = SystemClock.elapsedRealtime() - started
                    }
                    AppContainer.sharedOkHttpClient.dispatcher.runningCalls().forEach { call ->
                        val url = call.request().url
                        when {
                            url.host.endsWith("youtube.com") && url.encodedPath.startsWith("/youtubei/") -> phases += "YOUTUBE_PLAYER_API"
                            url.host.endsWith("youtube.com") && url.encodedPath.contains("/s/player/") -> phases += "YOUTUBE_PLAYER_JS"
                            url.host.endsWith("youtube.com") -> phases += "YOUTUBE_BOOTSTRAP"
                            url.host.endsWith("bilibili.com") && url.encodedPath.contains("playurl") -> phases += "BILI_PLAYURL"
                            url.host.endsWith("bilibili.com") -> phases += "BILI_METADATA_OR_SESSION"
                        }
                    }
                    delay(100)
                }
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
            instrumentation.sendStatus(2, Bundle().apply {
                putString("diagnostic_source", provider)
                putString("observed_http_stages", phases.joinToString(","))
                putString("player_error_kind", errorKind)
                putLong("first_address_observed_ms", firstAddressMs)
                putLong("first_player_error_ms", firstErrorMs)
                putInt("remaining_youtube_http_calls", AppContainer.sharedOkHttpClient.dispatcher.runningCalls().count {
                    it.request().url.host.endsWith("youtube.com")
                })
                putBoolean("pending_media_load", PlayerManager.pendingMediaLoadFlow.value)
            })
            events.cancel()
            instrumentation.runOnMainSync { PlayerManager.pause() }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
