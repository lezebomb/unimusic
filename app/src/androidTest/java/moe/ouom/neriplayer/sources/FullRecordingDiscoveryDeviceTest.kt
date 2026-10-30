package moe.ouom.neriplayer.sources

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.url.checkSourceAvailability
import moe.ouom.neriplayer.core.player.resolver.netease.recordingMetadata
import moe.ouom.neriplayer.core.startup.player.PlayerPreloadedInitializer
import moe.ouom.neriplayer.data.model.unified.RecordingMatchPolicy
import moe.ouom.neriplayer.data.model.unified.RecordingMetadata
import moe.ouom.neriplayer.data.unified.sourceCandidateForSong
import moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseSearchSongs
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.assessSourceChoice
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.discoverSourceChoices
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only production discovery/checks. Does not play, confirm matches or change settings. */
@RunWith(AndroidJUnit4::class)
class FullRecordingDiscoveryDeviceTest {
    @Test fun inspectOriginalFullRecordingCandidates(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("unimusicFullRecordingProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val report = JSONObject()
        val started = SystemClock.elapsedRealtime()
        var stage = "INITIALIZE"
        try {
            withTimeout(90_000) {
                val app = instrumentation.targetContext.applicationContext as Application
                withContext(Dispatchers.Main) { PlayerPreloadedInitializer(app).initialize() }
                report.put("automaticFallbackEnabled", AppContainer.settingsRepo.neteaseAutoSourceSwitchFlow.first())
                val title = args.getString("unimusicProbeTitle") ?: "雑踏、僕らの街"
                val artist = "トゲナシトゲアリ"
                val requested = RecordingMetadata(title, artist, 0)
                stage = "PRIMARY_METADATA"
                val raw = withTimeout(8_000) {
                    AppContainer.neteaseClient.searchSongsCancellable("$title $artist", limit = 12)
                }
                val song = parseNeteaseSearchSongs(raw).firstOrNull {
                    RecordingMatchPolicy.canonicalTitle(it.name) == RecordingMatchPolicy.canonicalTitle(title) &&
                        RecordingMatchPolicy.canonicalArtist(it.artist) == RecordingMatchPolicy.canonicalArtist(artist) &&
                        !RecordingMatchPolicy.hasVersionConflict(requested, it.recordingMetadata())
                }
                report.put("primaryFound", song != null)
                if (song != null) {
                    report.put("title", song.name).put("artist", song.artist).put("durationMs", song.durationMs)
                    stage = "DISCOVERY"
                    val discoveryStarted = SystemClock.elapsedRealtime()
                    val result = discoverSourceChoices(app, song, youtubeEnabled = false)
                    report.put("discoveryMs", SystemClock.elapsedRealtime() - discoveryStarted)
                    report.put("failures", JSONObject(result.failures.mapValues { it.value.name }))
                    report.put("candidates", JSONArray().apply {
                        result.candidates.filter { it.key.provider != "gdstudio_netease" }.forEach { candidate ->
                            val match = assessSourceChoice(song, candidate)
                            put(JSONObject().put("provider", candidate.key.provider)
                                .put("title", candidate.metadata.title).put("artist", candidate.metadata.artist)
                                .put("durationMs", candidate.metadata.durationMs)
                                .put("match", match.reason?.name ?: "ACCEPTED"))
                        }
                    })
                    stage = "ADDRESS_CHECK"
                    // At most two eligible candidates per automatic online provider, never GD or retired sources.
                    val primary = sourceCandidateForSong(song)
                    val checks = (listOfNotNull(primary) + result.candidates).distinctBy { it.key }.filter {
                        it.key.provider in setOf("netease", "kuwo", "bilibili") && assessSourceChoice(song, it).accepted
                    }.groupBy { it.key.provider }.values.flatMap { it.take(2) }
                    report.put("checks", JSONArray().apply {
                        for (candidate in checks) {
                            val checkStarted = SystemClock.elapsedRealtime()
                            val checked = PlayerManager.checkSourceAvailability(song, candidate)
                            put(JSONObject().put("provider", candidate.key.provider)
                                .put("primary", candidate.key == primary?.key)
                                .put("title", candidate.metadata.title).put("status", checked.status.name)
                                .put("elapsedMs", SystemClock.elapsedRealtime() - checkStarted))
                        }
                    })
                }
                stage = "COMPLETE"
            }
        } catch (_: Exception) {
            // Never send exception messages: HTTP failures can contain signed URLs or request headers.
            report.put("completed", false)
        } finally {
            report.put("stage", stage).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            instrumentation.sendStatus(2, Bundle().apply { putString("full_recording_probe", report.toString()) })
        }
        assertEquals("Discovery probe did not complete; inspect redacted stage report", "COMPLETE", stage)
    }
}
