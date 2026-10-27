package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import moe.ouom.neriplayer.data.model.unified.sync.*
import moe.ouom.neriplayer.data.sync.unified.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IdentityConflictRoomTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun freshLocalChoice() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = UnifiedLibraryRoomStore(db)
            val song = store.registerSource(SongSourceKey("netease", "42"), UnifiedSongMetadata("Original", "Artist", "Album", 180_000))
            val journal = UnifiedIdentityJournal(File(temporary.root, "conflict.json"))
            val backend = UnifiedIdentitySyncBackend(db, journal)
            backend.captureRoom()
            val remoteId = "00000000-0000-0000-0000-000000000001"
            backend.mergeRemote(UnifiedIdentityCodec.encode(UnifiedIdentityDataset(
                identities = listOf(PortableSongIdentity(remoteId, "netease", "42")), revisions = listOf(UnifiedSongRevision(remoteId,
                    UnifiedSyncDot("10000000-0000-0000-0000-000000000001", 1), metadata = PortableSongMetadata("Remote", "Artist", "Album", 180_000))))))
            fun edit(title: String) = db.openHelper.writableDatabase.execSQL("UPDATE unified_song SET title = ? WHERE song_id = ?", arrayOf(title, song.id))
            edit("Local edit")
            val oldView = backend.conflictsPage().items.single()
            edit("Newer local edit")
            val before = journal.export()
            try { backend.resolveConflict(oldView, oldView.choices.first());fail("Stale Room choice") }
            catch (_: StaleIdentityDecisionException) { }
            assertArrayEquals(before, journal.export())
            val current = backend.conflictsPage().items.single()
            backend.resolveConflict(current, current.choices.first { it.metadata?.title == "Remote" })
            assertEquals(0, backend.conflictsPage().total)
            assertEquals("Newer local edit", db.unifiedLibraryDao().song(song.id)?.title)
            assertEquals(1, store.songsPage().size)
            assertNull(db.unifiedLibraryDao().song(remoteId))
            assertEquals("Remote", journal.snapshot().songs.single().metadata?.title)
        } finally { db.close() }
    }
}
