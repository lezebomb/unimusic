package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity
import moe.ouom.neriplayer.data.model.SongItem
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
class UnifiedIdentityAliasRoomTest {
    @get:Rule val temporary = TemporaryFolder()
    private val foreignId = "00000000-0000-0000-0000-000000000001"
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val song = SongItem(42, "Original", "Artist", "Album", 0, 180_000, null,
        channelId = "netease", audioId = "42")
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
        NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
    private fun remote(metadata: PortableSongMetadata?, counter: Long = 1, context: Map<String, Long> = emptyMap()) =
        UnifiedIdentityCodec.encode(UnifiedIdentityDataset(identities = listOf(PortableSongIdentity(foreignId, "netease", "42")),
            revisions = listOf(UnifiedSongRevision(foreignId, UnifiedSyncDot(actor, counter), context, metadata))))

    @Test fun foreignUuidUsesExistingNativeTrackWithoutRekeyingOrTransientUrls() = runTest {
        val db = database()
        try {
            val native = UnifiedPlaybackRoomStore(db)
            val local = native.identify(song)
            val durable = song.copy(customName = "My title", userLyricOffsetMs = 120)
            val identity = durable.identity()
            db.localPlaylistDao().insertTracks(listOf(TrackEntity(durable.stableKey(), identity.id, identity.album,
                identity.mediaUri, durable.id, durable.name, durable.artist, durable.album, durable.albumId,
                durable.durationMs, null, durable.mediaUri, durable.channelId, durable.audioId, durable.subAudioId,
                null, null, null, 1, Gson().toJson(durable.copy(streamUrl = "https://example.test/transient")))))
            val journal = UnifiedIdentityJournal(File(temporary.root, "alias.json"))
            val backend = UnifiedIdentitySyncBackend(db, journal)
            backend.captureRoom()
            val metadata = journal.snapshot().songs.single().metadata!!
            backend.mergeRemote(remote(metadata))
            val lookup = backend.lookupLocal(foreignId)
            assertEquals(LocalIdentityLookupStatus.READY, lookup.status)
            assertEquals(listOf(local.songId), lookup.localSongIds)
            val resolved = UnifiedPlaybackBackend(native, backend).load(foreignId)!!
            assertEquals(local.songId, resolved.songId) // Existing source decisions still use the native Room key.
            assertEquals(durable.stableKey(), resolved.song.stableKey())
            assertEquals("My title", resolved.song.customName)
            assertEquals(120L, resolved.song.userLyricOffsetMs);assertNull(resolved.song.streamUrl)
            assertNull(db.unifiedLibraryDao().song(foreignId))
            assertEquals(1, UnifiedLibraryRoomStore(db).songsPage().size)
            assertEquals(LocalIdentityLookupStatus.INVALID_ID, backend.lookupLocal("42").status)
        } finally { db.close() }
    }

    @Test fun sameFileRevisionInvalidatesAliasCacheAndForeignDeletesOrConflictsNeverGuessARecording() = runTest {
        val db = database()
        try {
            val native = UnifiedPlaybackRoomStore(db)
            val local = native.identify(song)
            val file = File(temporary.root, "cache.json")
            val journal = UnifiedIdentityJournal(file)
            val backend = UnifiedIdentitySyncBackend(db, journal)
            backend.captureRoom()
            val metadata = journal.snapshot().songs.single().metadata!!
            backend.mergeRemote(remote(metadata))
            assertEquals(LocalIdentityLookupStatus.READY, backend.lookupLocal(foreignId).status)
            val playback = UnifiedPlaybackBackend(native, backend)
            val oldTarget = requireNotNull(playback.load(foreignId))
            assertTrue(playback.isCurrent(oldTarget))
            val otherInstance = UnifiedIdentityJournal(file)
            otherInstance.mergeRemote(remote(null, 2, mapOf(actor to 1)))
            assertEquals(LocalIdentityLookupStatus.DELETED, backend.lookupLocal(foreignId).status)
            assertFalse(playback.isCurrent(oldTarget))
            assertNull(playback.load(foreignId))
            assertNotNull(playback.load(local.songId)) // No automatic deletion of the existing user library.
            otherInstance.restore(foreignId, metadata, listOf(PortableSongIdentity(foreignId, "netease", "42")))
            assertEquals(LocalIdentityLookupStatus.READY, backend.lookupLocal(foreignId).status)
            otherInstance.mergeRemote(remote(metadata.copy(title = "Concurrent edit"), 3, mapOf(actor to 2)))
            assertEquals(LocalIdentityLookupStatus.CONFLICT, backend.lookupLocal(foreignId).status)
            assertNull(playback.load(foreignId));assertNotNull(playback.load(local.songId))
            assertEquals(1, UnifiedLibraryRoomStore(db).songsPage().size)
        } finally { db.close() }
    }
}
