package moe.ouom.neriplayer.sources

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.bilibili.playback.PlayOptions
import moe.ouom.neriplayer.data.model.unified.RecordingMatchPolicy
import moe.ouom.neriplayer.data.model.unified.RecordingMetadata
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseSearchSongs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One opt-in public-video audit. A muted isolated decoder leaves the user's queue/history untouched. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PublishedBiliAudioDeviceTest {
    @Test fun inspectPublicationAndDecodeTail(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("unimusicPublishedBiliProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val report = JSONObject()
        val began = SystemClock.elapsedRealtime()
        val playerError = AtomicReference<String?>(null)
        var player: ExoPlayer? = null
        var stage = "VIDEO_METADATA"
        try {
            withTimeout(90_000) {
                val bvid = args.getString("unimusicProbeBvid") ?: "BV1HVQHYNEBg"
                require(bvid.matches(Regex("BV[0-9A-Za-z]{10}")))
                val info = withTimeout(8_000) { AppContainer.biliClient.getVideoBasicInfoByBvid(bvid) }
                assertEquals(bvid, info.bvid)
                require(info.aid > 0 && info.ownerMid > 0)
                val page = info.pages.single().also { require(it.cid > 0) }
                report.put("video", bvid).put("title", info.title).put("ownerMid", info.ownerMid)
                    .put("ownerName", info.ownerName).put("videoDurationMs", page.durationSec * 1000L)
                stage = "PUBLISHER_METADATA"
                val certificate = AtomicReference(JSONObject().put("status", "NOT_OBSERVED"))
                val auditHttp = AppContainer.sharedOkHttpClient.newBuilder().addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (chain.request().url.encodedPath == "/x/space/wbi/acc/info" &&
                        chain.request().url.queryParameter("mid") == info.ownerMid.toString()) {
                        certificate.set(runCatching {
                            val root = JSONObject(response.peekBody(256 * 1024L).string())
                            val data = root.optJSONObject("data")
                            val official = data?.optJSONObject("official")
                            JSONObject().put("httpCode", response.code).put("code", root.optInt("code", -1))
                                .put("midMatchesRequest", data?.opt("mid")?.toString() == info.ownerMid.toString())
                                .put("officialType", official?.optInt("type", -1) ?: -1)
                                .put("officialTitle", official?.optString("title").orEmpty())
                        }.getOrElse { JSONObject().put("status", "UNPARSEABLE") })
                    }
                    response
                }.build()
                try {
                    report.put("profileCompleted", withTimeoutOrNull(6_000) {
                        BiliClient(AppContainer.biliCookieRepo, auditHttp).getUploaderProfile(info.ownerMid)
                        true
                    } ?: false)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { report.put("profileFailureType", failure.javaClass.simpleName) }
                report.put("certificate", certificate.get())
                stage = "CATALOG_METADATA"
                val title = args.getString("unimusicProbeTitle") ?: "ダレモ"
                val artist = "トゲナシトゲアリ"
                val requested = RecordingMetadata(title, artist, 0)
                val catalog = withTimeoutOrNull(8_000) {
                    val raw = AppContainer.neteaseClient.searchSongsCancellable("$title $artist", limit = 12)
                    parseNeteaseSearchSongs(raw).firstOrNull {
                        RecordingMatchPolicy.canonicalTitle(it.name) == RecordingMatchPolicy.canonicalTitle(title) &&
                            RecordingMatchPolicy.canonicalArtist(it.artist) == RecordingMatchPolicy.canonicalArtist(artist) &&
                            !RecordingMatchPolicy.hasVersionConflict(requested,
                                RecordingMetadata(it.name, it.artist, it.durationMs, versionContext = it.album))
                    }
                }
                report.put("catalogFound", catalog != null)
                catalog?.let {
                    report.put("catalogTitle", it.name).put("catalogArtist", it.artist)
                        .put("catalogDurationMs", it.durationMs)
                        .put("nativeArtistIds", JSONArray(it.neteaseArtists.orEmpty().map { artist -> artist.id }))
                }
                stage = "AUDIO_PERMISSION"
                val playInfo = withTimeout(8_000) {
                    AppContainer.biliClient.getPlayInfoByBvid(bvid, page.cid, PlayOptions(tryLook = 0))
                }
                val data = playInfo.raw.optJSONObject("data") ?: playInfo.raw
                val preview = data.optBoolean("is_preview") || data.optInt("is_preview") == 1
                val drm = data.optBoolean("is_drm") || data.optInt("is_drm") == 1 || data.optInt("drm_type") > 0
                report.put("preview", preview).put("drm", drm).put("apiDurationMs", playInfo.timeLengthMs)
                require(!preview && !drm)
                val streams = with(AppContainer.biliClient) { playInfo.toAudioStreamInfos() }
                val stream = streams.first { it.id in setOf(30216, 30232, 30280) && it.url.startsWith("https://") }
                require(stream.mimeType.startsWith("audio/", ignoreCase = true))
                report.put("streamMimeType", stream.mimeType)
                stage = "DECODE_START"
                withContext(Dispatchers.Main) {
                    val factory = OkHttpDataSource.Factory(AppContainer.sharedOkHttpClient)
                        .setDefaultRequestProperties(mapOf(
                            "Referer" to "https://www.bilibili.com/video/$bvid/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
                        ))
                    player = ExoPlayer.Builder(context)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 4_000, 250, 500).build())
                        .build().also {
                            it.setAudioAttributes(AudioAttributes.DEFAULT, false)
                            it.volume = 0f
                            it.addListener(object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) { playerError.set(error.errorCodeName) }
                            })
                            it.setMediaItem(MediaItem.Builder().setUri(stream.url).setMimeType(stream.mimeType).build())
                            it.prepare(); it.play()
                        }
                }
                suspend fun awaitPosition(minimum: Long) = withTimeout(18_000) {
                    while (true) {
                        check(playerError.get() == null)
                        if (withContext(Dispatchers.Main) { player!!.isPlaying && player!!.currentPosition >= minimum }) break
                        delay(100)
                    }
                }
                awaitPosition(1_000)
                withContext(Dispatchers.Main) {
                    val groups = player!!.currentTracks.groups
                    val audioPresent = groups.any { it.type == C.TRACK_TYPE_AUDIO }
                    val videoPresent = groups.any { it.type == C.TRACK_TYPE_VIDEO }
                    report.put("audioTrackPresent", audioPresent).put("videoTrackPresent", videoPresent)
                    require(audioPresent && !videoPresent)
                }
                val duration = withContext(Dispatchers.Main) { player!!.duration }
                require(duration > 90_000)
                report.put("decodedDurationMs", duration)
                val target = duration - 4_000
                stage = "DECODE_TAIL"
                withContext(Dispatchers.Main) { player!!.seekTo(target) }
                awaitPosition(target + 1_000)
                withContext(Dispatchers.Main) {
                    report.put("tailPositionMs", player!!.currentPosition)
                        .put("renderedAudioBuffers", player!!.audioDecoderCounters?.renderedOutputBufferCount ?: 0)
                }
                stage = "TRANSPORT_COMPLETE"
            }
        } catch (failure: Exception) {
            report.put("failureType", failure.javaClass.simpleName)
        } finally {
            withContext(Dispatchers.Main) { player?.release() }
            report.put("stage", stage).put("elapsedMs", SystemClock.elapsedRealtime() - began)
                .put("playerError", playerError.get()).put("automaticOriginalMatchVerified", false)
            instrumentation.sendStatus(2, Bundle().apply { putString("publication_audio_probe", report.toString()) })
        }
        assertTrue("Bounded public-video transport probe incomplete; inspect redacted stage report", stage == "TRANSPORT_COMPLETE")
    }
}
