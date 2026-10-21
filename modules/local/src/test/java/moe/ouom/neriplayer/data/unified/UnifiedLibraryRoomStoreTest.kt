package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberTokenEntity
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistOriginEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UnifiedPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UnifiedPlaylistMemberEntity
import moe.ouom.neriplayer.data.model.unified.RecordingVersion
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnifiedLibraryRoomStoreTest {
    private lateinit var db: NeriUserDataDatabase
    private lateinit var store: UnifiedLibraryRoomStore
    private val metadata = UnifiedSongMetadata("同名歌曲", "歌手", "专辑", 180_000)

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        store = UnifiedLibraryRoomStore(db) { 1234L }
    }

    @After fun close() = db.close()

    @Test fun repeatedExactSourceRetainsUuidAndNeverGrantsPlaybackRights() = runTest {
        val key = SongSourceKey("netease", "42")
        val first = store.registerSource(key, metadata, "old-key")
        val concurrent = (1..8).map { async { store.registerSource(key, metadata) } }.awaitAll()
        assertEquals(setOf(first.id), concurrent.map { it.id }.toSet())
        val second = store.registerSource(key, metadata.copy(title = "新标题"), "another-old-key")
        assertEquals(first.id, second.id)
        assertNotEquals("42", first.id)
        assertEquals(first, store.songForLegacy("old-key"))
        assertEquals(first, store.songForLegacy("another-old-key"))
        assertEquals(1, store.songsPage().size)
        val source = db.unifiedLibraryDao().sources(first.id).single()
        assertEquals("METADATA_ONLY", source.playbackMode)
        assertEquals("UNKNOWN", source.availability)
        assertNull(source.permissionExpiresAt)
    }

    @Test fun sameTitleDifferentPlatformPartOrVersionContextStaysSeparate() = runTest {
        val studio = store.registerSource(SongSourceKey("netease", "1"), metadata)
        val cover = store.registerSource(SongSourceKey("bilibili", "1", "100"),
            metadata.copy(version = RecordingVersion.COVER))
        val live = store.registerSource(SongSourceKey("bilibili", "1", "200"),
            metadata.copy(version = RecordingVersion.LIVE))
        val recording = store.registerSource(SongSourceKey("bilibili", "1", "200", "recording-2"), metadata)
        assertEquals(4, setOf(studio.id, cover.id, live.id, recording.id).size)
    }

    @Test fun failedLegacyRebindRollsBackNewSongAndSource() = runTest {
        val original = store.registerSource(SongSourceKey("netease", "1"), metadata, "old")
        var rejected = false
        try { store.registerSource(SongSourceKey("bilibili", "2", "3"), metadata, "old") }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(listOf(original), store.songsPage())
        assertNull(db.unifiedLibraryDao().source("bilibili", "2", "3", ""))
    }

    @Test fun unlinkOriginPreservesOwnedPlaylistAndSongs() = runTest {
        val song = store.registerSource(SongSourceKey("netease", "7"), metadata)
        val dao = db.unifiedLibraryDao()
        dao.insertPlaylist(UnifiedPlaylistEntity("playlist", 17L, "我的歌单", "cover", 1L, false))
        dao.insertMembersIfAbsent(listOf(UnifiedPlaylistMemberEntity("playlist", song.id, 0, 1L, 1L, false)))
        dao.insertOrigin(PlaylistOriginEntity("origin", "playlist", "netease", "public-user-id",
            "remote-playlist", "LINKED", true, 1L, null, null))
        store.unlinkOrigin("origin")
        assertEquals(false, dao.origin("netease", "public-user-id", "remote-playlist")?.linked)
        assertEquals("我的歌单", dao.playlistForLegacy(17L)?.name)
        assertEquals(song, store.songsPage().single())
        assertEquals(song.id, dao.membersPage("playlist", 10, 0).single().songId)
    }

    @Test fun backfillResumesWithoutChangingLegacyRowsOrderOrDeletionTokens() = runTest {
        val legacy = db.localPlaylistDao()
        val tracks = listOf(track("z", 1, "netease", null), track("a", 2, "bilibili", "100"),
            track("m", 2, "bilibili", "200"))
        legacy.insertPlaylists(listOf(LocalPlaylistEntity(99, "收藏", 0, "cover", 10, 1, true)))
        legacy.insertTracks(tracks)
        val members = tracks.mapIndexed { position, song ->
            PlaylistMemberEntity(99, song.identityKey, position, 100L + position, position, null, 1, "{}")
        }
        legacy.insertMembers(members)
        val token = PlaylistMemberTokenEntity(99, "z", "device", 42, 0)
        legacy.insertMemberTokens(listOf(token))
        assertEquals(1, store.backfillLegacySongsPage(1))
        val first = store.songForLegacy("a")
        store = UnifiedLibraryRoomStore(db) { 9999L } // Simulate a restarted worker.
        assertEquals(2, store.backfillLegacySongsPage(2))
        assertEquals(0, store.backfillLegacySongsPage(1))
        assertEquals(first, store.songForLegacy("a"))
        assertEquals(2, store.backfillLegacyPlaylistPage(99, 2))
        assertEquals(1, store.backfillLegacyPlaylistPage(99, 2))
        assertEquals(0, store.backfillLegacyPlaylistPage(99, 2))
        val playlist = requireNotNull(db.unifiedLibraryDao().playlistForLegacy(99))
        val expected = tracks.map { requireNotNull(store.songForLegacy(it.identityKey)).id }
        assertEquals(expected, db.unifiedLibraryDao().membersPage(playlist.playlistId, 10, 0).map { it.songId })
        db.openHelper.writableDatabase.execSQL(
            "UPDATE unified_playlist_member SET deleted = 1 WHERE playlist_id = ? AND song_id = ?",
            arrayOf(playlist.playlistId, expected.first())
        )
        assertEquals(0, store.backfillLegacyPlaylistPage(99, 3))
        assertEquals(expected.drop(1), db.unifiedLibraryDao().membersPage(playlist.playlistId, 10, 0).map { it.songId })
        assertEquals(tracks.sortedBy { it.identityKey }, legacy.getTracks().sortedBy { it.identityKey })
        assertEquals(members, legacy.getMembersForPlaylist(99))
        assertEquals(listOf(token), legacy.getMemberTokensForPlaylist(99))
        // A newly inserted earlier key/position is still found after the prior pass completed.
        legacy.insertTracks(listOf(track("0", 9, "netease", null)))
        legacy.insertMembers(listOf(PlaylistMemberEntity(99, "0", -1, 1L, -1, null, 1, "{}")))
        assertEquals(1, store.backfillLegacySongsPage(1))
        assertEquals(1, store.backfillLegacyPlaylistPage(99, 1))
        assertEquals(store.songForLegacy("0")?.id,
            db.unifiedLibraryDao().membersPage(playlist.playlistId, 10, 0).first().songId)
        assertEquals(false, store.backfillLegacyBatch(1))
        legacy.insertPlaylists(listOf(LocalPlaylistEntity(100, "空歌单", 1, null, 11, 1, false)))
        assertEquals(true, store.backfillLegacyBatch(1))
        assertEquals(false, store.backfillLegacyBatch(1))
        assertEquals("空歌单", db.unifiedLibraryDao().playlistForLegacy(100)?.name)
    }

    private fun track(key: String, id: Long, provider: String, part: String?): TrackEntity = TrackEntity(
        identityKey = key, identityId = id, identityAlbum = "album", identityMediaUri = null,
        songId = id, name = metadata.title, artist = metadata.artist, album = metadata.album,
        albumId = 0, durationMs = metadata.durationMs, coverUrl = "cover", mediaUri = null,
        channelId = provider, audioId = id.toString(), subAudioId = part, sourceStableKey = null,
        localFileName = null, localFilePath = null, payloadSchemaVersion = 1,
        durablePayloadJson = "{\"customName\":\"保留\"}"
    )
}
