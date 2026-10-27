package moe.ouom.neriplayer.sources

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.streamOnlyPlaybackUrl
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.unified.PlaylistImportMode
import moe.ouom.neriplayer.data.model.unified.PlaylistImportState
import moe.ouom.neriplayer.data.unified.UnifiedPlaybackRoomStore
import moe.ouom.neriplayer.platform.netease.importing.NeteasePlaylistImportProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-account checks. Repeats existing small imports; never deletes library or cloud content. */
@RunWith(AndroidJUnit4::class)
class NeteaseImportDeviceTest {
    @Test fun importedOrderUuidAndRepeatArePreserved() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("unimusicLiveImport") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cookies = withTimeout(5_000) { AppContainer.neteaseCookieRepo.cookieFlow.first { !it["MUSIC_U"].isNullOrBlank() } }
        AppContainer.neteaseClient.setPersistedCookies(cookies)
        val backend = AppContainer.playlistImportBackend
        val jobs = backend.observeJobs().first().filter { it.provider == "netease" &&
            it.mode == PlaylistImportMode.ONE_SHOT && it.state == PlaylistImportState.COMPLETED && it.total in 1..4 }.take(2)
        assertEquals("Two completed small UI imports are required", 2, jobs.size)
        assertEquals(1, jobs.map { it.accountKey }.distinct().size)
        val provider = NeteasePlaylistImportProvider(AppContainer.neteaseClient)
        val repository = LocalPlaylistRepository.getInstance(instrumentation.targetContext)
        assertTrue(repository.awaitInitialized())
        val db = NeriUserDataDatabase.getInstance(instrumentation.targetContext)
        val uuidPlayback = UnifiedPlaybackRoomStore(db)
        val before = linkedMapOf<String, List<String>>()
        val publicSongIds = linkedSetOf<String>()
        for (job in jobs) {
            val snapshot = provider.readSnapshot(job.accountKey, job.remotePlaylistId)
            val expected = snapshot.resourceIds.distinct()
            val legacyId = requireNotNull(db.unifiedLibraryDao().playlist(requireNotNull(job.playlistId))).legacyPlaylistId
            val playlist = repository.playlists.value.single { it.id == legacyId }
            val actual = playlist.songs.map { it.audioId ?: it.id.toString() }
            assertEquals("Imported source order changed", expected, actual)
            assertEquals(expected.size, job.imported)
            assertEquals(0, job.failed)
            assertEquals(0, job.skipped)
            for (song in playlist.songs) {
                val target = uuidPlayback.identify(song)
                assertEquals(song.audioId, uuidPlayback.load(target.songId)?.song?.audioId)
            }
            before[job.jobId] = actual
            publicSongIds += actual
        }
        for (job in jobs) assertEquals(job.jobId, backend.enqueue(job.accountKey, job.remotePlaylistId, job.mode))
        withTimeout(45_000) {
            while (jobs.any { backend.progress(it.jobId)?.state != PlaylistImportState.COMPLETED }) delay(150)
        }
        for (job in jobs) {
            val progress = requireNotNull(backend.progress(job.jobId))
            val legacyId = requireNotNull(db.unifiedLibraryDao().playlist(requireNotNull(progress.playlistId))).legacyPlaylistId
            val playlist = repository.playlists.value.single { it.id == legacyId }
            assertEquals("Repeat import duplicated or reordered songs", before[job.jobId], playlist.songs.map { it.audioId ?: it.id.toString() })
        }
        db.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(23, cursor.getInt(0))
        }
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        instrumentation.sendStatus(2, Bundle().apply {
            putInt("imported_playlists", jobs.size)
            putInt("imported_memberships", before.values.sumOf { it.size })
            putInt("distinct_song_ids", publicSongIds.size)
            putBoolean("order_verified", true); putBoolean("uuid_verified", true)
            putBoolean("repeat_without_duplicates", true); putInt("room_version", 23)
        })
    }

    @Test fun importedNeteaseSongDecodesWithoutFallback() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("unimusicLiveImport") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cookies = withTimeout(5_000) { AppContainer.neteaseCookieRepo.cookieFlow.first { !it["MUSIC_U"].isNullOrBlank() } }
        AppContainer.neteaseClient.setPersistedCookies(cookies)
        val repository = LocalPlaylistRepository.getInstance(context)
        assertTrue(repository.awaitInitialized())
        val db = NeriUserDataDatabase.getInstance(context)
        val jobs = AppContainer.playlistImportBackend.observeJobs().first().filter {
            it.provider == "netease" && it.state == PlaylistImportState.COMPLETED && it.total in 1..4
        }.take(2)
        val ids = jobs.mapNotNull { it.playlistId }.mapNotNull { db.unifiedLibraryDao().playlist(it)?.legacyPlaylistId }.toSet()
        val songs = repository.playlists.value.filter { it.id in ids }.flatMap { it.songs }.distinctBy { it.id }.take(5)
        assertTrue("Previously imported songs required", songs.isNotEmpty())
        var checked = 0
        val started = SystemClock.elapsedRealtime()
        val eligible = songs.firstNotNullOfOrNull { song ->
            checked++
            val response = withTimeout(4_000) { AppContainer.neteaseClient.getSongDownloadUrlCancellable(song.id, level = "standard") }
            val parsed = NeteasePlaybackResponseParser.parsePlayback(response, song.durationMs)
            if (parsed is NeteasePlaybackResponseParser.PlaybackResult.Success && parsed.notice == null && parsed.songId == song.id) {
                song.copy(streamUrl = streamOnlyPlaybackUrl(parsed.url))
            } else null
        }
        requireNotNull(eligible) { "No full normal NetEase address among imported samples" }
        val resolveMs = SystemClock.elapsedRealtime() - started
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val playStarted = SystemClock.elapsedRealtime()
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, minOf(volume, 3), 0)
            instrumentation.runOnMainSync {
                PlayerManager.initialize(context.applicationContext as Application)
                PlayerManager.pause()
                PlayerManager.playPlaylist(listOf(eligible), 0)
            }
            withTimeout(20_000) {
                while (PlayerManager.currentSongFlow.value?.audioId != eligible.audioId ||
                    !PlayerManager.isPlayingFlow.value || PlayerManager.playbackPositionFlow.value < 1_500) delay(100)
            }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("source", "netease_account_native"); putString("outcome", "PLAYED")
                putInt("candidates_checked", checked); putBoolean("full_address_no_preview", true)
                putLong("resolve_ms", resolveMs)
                putLong("play_request_to_observed_position_ms", SystemClock.elapsedRealtime() - playStarted)
                putLong("observed_position_ms", PlayerManager.playbackPositionFlow.value)
            })
        } finally {
            instrumentation.runOnMainSync { PlayerManager.pause() }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }
}
