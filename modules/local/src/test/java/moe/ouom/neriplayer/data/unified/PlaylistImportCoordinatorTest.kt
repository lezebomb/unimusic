package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore
import moe.ouom.neriplayer.data.local.playlist.InMemoryLocalPlaylistSyncMutationStore
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.unified.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaylistImportCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: NeriUserDataDatabase
    private lateinit var repo: LocalPlaylistRepository
    private lateinit var store: PlaylistImportRoomStore
    private lateinit var fake: FakeMusicProvider
    private val mutations = InMemoryLocalPlaylistSyncMutationStore()
    private var time = 1_000_000L
    private var autoSync = false
    private var rejectSync = false
    private var syncRequests = 0

    @Before fun open() {
        reopen()
        fake = FakeMusicProvider("netease", "7")
        (1..5).forEach { id -> fake.songs[id.toString()] = ImportSong(SongSourceKey("netease", id.toString()),
            UnifiedSongMetadata("歌曲$id", "歌手", "专辑", 180_000), "cover") }
        snapshot("first", listOf("3", "1", "2"))
    }

    private fun reopen() {
        db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, "imp.db")
            .allowMainThreadQueries().build()
        repo = LocalPlaylistRepository.createForTest(context, File(context.cacheDir, "import.json"),
            roomStore = LocalPlaylistRoomStore(db), syncMutationStore = mutations, autoSyncEnabled = autoSync,
            autoSyncTrigger = { if (rejectSync) throw java.io.IOException("fixture") else syncRequests++ })
        store = PlaylistImportRoomStore(db, repo) { time }
    }

    @After fun close() { db.close(); context.deleteDatabase("imp.db") }
    private fun snapshot(revision: String, ids: List<String>) {
        fake.snapshots["10"] = ImportPlaylistSnapshot("netease", "7",
            ImportPlaylistSummary("10", "源歌单", "playlist-cover", ids.size), revision, ids)
    }
    private suspend fun enqueue() = store.enqueue("7", "10", PlaylistImportMode.LINKED)
    private suspend fun run(id: String, pages: Int = 20) = PlaylistImportCoordinator(store, fake, 1).run(id, pages)

    @Test fun automaticRefreshKeepsEditsAndHonorsCancellation() = runTest {
        val id = enqueue()
        assertFalse(store.refreshLinked(id, onlyCompleted = true))
        run(id)
        val playlist = repo.playlists.value.single()
        repo.renamePlaylist(playlist.id, "My title")
        repo.removeSongFromPlaylist(playlist.id, 1L)
        snapshot("automatic", listOf("2", "1", "4"))
        assertTrue(store.refreshLinked(id, onlyCompleted = true))
        assertFalse(store.refreshLinked(id, onlyCompleted = true)) // Already queued: do not replace ownership.
        run(id)
        assertEquals("My title", repo.playlists.value.single().name)
        assertEquals(listOf(3L, 2L, 4L), repo.playlists.value.single().songs.map { it.id })
        store.cancel(id)
        assertFalse(store.refreshLinked(id, onlyCompleted = true))
        store.unlink(id)
        assertFalse(store.refreshLinked(id, onlyCompleted = true))
        assertFalse(store.refreshLinked(id))
        assertEquals(3, repo.playlists.value.single().songs.size)
    }

    @Test fun durableResumeAndDedup() = runTest {
        snapshot("first", listOf("3", "1", "3", "2"))
        val id = enqueue()
        assertEquals(PlaylistImportRunResult.RETRY, run(id, 1))
        assertEquals(1, store.progress(id)?.imported)
        assertEquals(listOf(1, 3), db.playlistImportDao().pending(id, 10).map { it.position })
        val uuid = store.songs.songsPage().single().id
        db.close(); reopen() // Reopen the actual on-disk database and repository.
        assertEquals(PlaylistImportRunResult.FINISHED, run(id))
        val playlist = repo.playlists.value.single()
        assertEquals(listOf(3L, 1L, 2L), playlist.songs.map { it.id })
        assertEquals("playlist-cover", playlist.customCoverUrl)
        assertTrue(playlist.songs.all { it.syncMembershipTokens?.size == 1 })
        assertEquals(3, store.progress(id)?.total)
        assertEquals(id, enqueue())
        run(id)
        assertEquals(playlist, repo.playlists.value.single())
        assertTrue(uuid in store.songs.songsPage().map { it.id })
        assertEquals(3, store.songs.songsPage().size)
    }

    @Test fun retryKeepsSourcePosition() = runTest {
        fake.songFailures["1"] = ImportFailureKind.TEMPORARY
        val id = enqueue(); run(id)
        assertEquals(PlaylistImportState.PARTIAL_FAILED, store.progress(id)?.state)
        val failure = store.failures(id).single()
        assertEquals(1, failure.position)
        assertEquals(1, failure.attempts)
        assertFalse(store.retry(id))
        time += 31_000
        fake.songFailures.clear()
        assertTrue(store.retry(id)); run(id)
        assertEquals(listOf(3L, 1L, 2L), repo.playlists.value.single().songs.map { it.id })
        assertEquals(PlaylistImportState.COMPLETED, store.progress(id)?.state)
        assertEquals(3, store.songs.songsPage().size)
    }

    @Test fun refreshAndUnlinkKeepEdits() = runTest {
        val id = enqueue(); run(id)
        val playlist = repo.playlists.value.single()
        repo.renamePlaylist(playlist.id, "用户的名字")
        repo.removeSongFromPlaylist(playlist.id, 1L)
        snapshot("second", listOf("2", "1", "4"))
        enqueue(); run(id)
        assertEquals("用户的名字", repo.playlists.value.single().name)
        assertEquals("playlist-cover", repo.playlists.value.single().customCoverUrl)
        assertEquals(listOf(3L, 2L, 4L), repo.playlists.value.single().songs.map { it.id })
        assertEquals(1, store.progress(id)?.skipped)
        snapshot("empty", emptyList())
        enqueue(); run(id)
        assertEquals(listOf(3L, 2L, 4L), repo.playlists.value.single().songs.map { it.id })
        store.unlink(id)
        assertFalse(requireNotNull(db.unifiedLibraryDao().origin("netease", "7", "10")).linked)
        assertFalse(store.refreshLinked(id))
        assertEquals(3, repo.playlists.value.single().songs.size)
    }

    @Test fun cancelledRunCannotCommit() = runTest {
        val id = enqueue()
        fake.beforeRequest = { if (it == "songs") store.cancel(id) }
        assertEquals(PlaylistImportRunResult.SUPERSEDED, run(id))
        assertEquals(PlaylistImportState.CANCELLED, store.progress(id)?.state)
        assertTrue(repo.playlists.value.isEmpty())
        assertTrue(store.songs.songsPage().isEmpty())
        fake.beforeRequest = {}
        assertTrue(store.retry(id)); run(id)
        assertEquals(3, repo.playlists.value.single().songs.size)
    }

    @Test fun authAndIncompleteBatchesPause() = runTest {
        val id = enqueue()
        fake.activeAccountKey = null
        run(id)
        assertEquals(PlaylistImportState.PAUSED_AUTH, store.progress(id)?.state)
        fake.activeAccountKey = "7"; store.retry(id)
        val incomplete = object : PlaylistImportProvider by fake {
            override suspend fun readSongs(accountKey: String, resourceIds: List<String>) = ImportSongBatch(emptyList(), emptyList())
        }
        PlaylistImportCoordinator(store, incomplete).run(id)
        assertEquals(ImportFailureKind.INCOMPLETE_RESPONSE, store.progress(id)?.lastError)
        assertTrue(repo.playlists.value.isEmpty())
        store.retry(id)
        val switched = object : PlaylistImportProvider by fake {
            override suspend fun readSongs(accountKey: String, resourceIds: List<String>): ImportSongBatch {
                val batch = fake.readSongs(accountKey, resourceIds)
                fake.activeAccountKey = "8"
                return batch
            }
        }
        PlaylistImportCoordinator(store, switched).run(id)
        assertEquals(ImportFailureKind.ACCOUNT_CHANGED, store.progress(id)?.lastError)
        assertTrue(store.songs.songsPage().isEmpty())
    }

    @Test fun pageFailureRollsBackEverything() = runTest {
        val id = enqueue()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON track BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        run(id)
        assertEquals(ImportFailureKind.LOCAL_STORAGE, store.progress(id)?.lastError)
        assertEquals(0, store.progress(id)?.imported)
        assertTrue(repo.playlists.value.isEmpty())
        assertTrue(db.localPlaylistDao().getPlaylists().isEmpty())
        assertTrue(store.songs.songsPage().isEmpty())
        assertNull(db.unifiedLibraryDao().origin("netease", "7", "10"))
        assertNull(LocalPlaylistRoomStore(db).readPendingSyncMutationOutbox())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_import")
        assertTrue(store.retry(id)); run(id)
        assertEquals(3, repo.playlists.value.single().songs.size)
    }

    @Test fun syncReplaySurvivesRestart() = runTest {
        db.close(); autoSync = true; rejectSync = true; reopen()
        val id = enqueue(); PlaylistImportCoordinator(store, fake, 100).run(id)
        assertEquals(PlaylistImportState.COMPLETED, store.progress(id)?.state)
        val outbox = requireNotNull(LocalPlaylistRoomStore(db).readPendingSyncMutationOutbox())
        assertTrue(outbox.mutations.any { it.playlistDataChanged })
        db.close(); rejectSync = false; reopen()
        assertTrue(syncRequests > 0)
        assertNull(LocalPlaylistRoomStore(db).readPendingSyncMutationOutbox())
        assertEquals(3, repo.playlists.value.single().songs.size)
    }
}
