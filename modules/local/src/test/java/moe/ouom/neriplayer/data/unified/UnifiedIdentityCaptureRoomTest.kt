package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import moe.ouom.neriplayer.data.sync.unified.UnifiedIdentityCodec
import moe.ouom.neriplayer.data.sync.unified.UnifiedIdentityJournal
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnifiedIdentityCaptureRoomTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun portableCapture() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = UnifiedLibraryRoomStore(db)
            val metadata = UnifiedSongMetadata("Fixture", "Artist", "Album", 180_000)
            val publicSong = store.registerSource(SongSourceKey("netease", "42"), metadata, "private-legacy-key")
            store.registerSource(SongSourceKey("legacy", "private-digest"), metadata)
            store.registerSource(SongSourceKey("bilibili", "BV1234567890"), metadata)
            store.registerSource(SongSourceKey("qqmusic", "https://private/?token=secret"), metadata)
            val journal = UnifiedIdentityJournal(File(temporary.root, "identities.json"))
            val backend = UnifiedIdentitySyncBackend(db, journal)
            val first = backend.exportCurrent()
            assertEquals(4, first.capture.scannedSources)
            assertEquals(1, first.capture.excludedDeviceSources)
            assertEquals(2, first.capture.excludedUnsupportedSources)
            assertEquals(1, first.capture.observedSongs)
            assertEquals(1, first.capture.journal.changedSongs)
            val portable = UnifiedIdentityCodec.decode(first.bytes)
            assertEquals(listOf(publicSong.id), portable.identities.map { it.songId })
            assertFalse(String(first.bytes).contains("private"));assertFalse(String(first.bytes).contains("token"))
            assertEquals(0, backend.captureRoom().journal.changedSongs)
            assertEquals(4, store.songsPage().size)
            assertEquals(publicSong, store.songForLegacy("private-legacy-key"))
            assertEquals("METADATA_ONLY", db.unifiedLibraryDao().sources(publicSong.id).single().playbackMode)
            assertEquals(23, db.openHelper.readableDatabase.version)
        } finally { db.close() }
    }
}
