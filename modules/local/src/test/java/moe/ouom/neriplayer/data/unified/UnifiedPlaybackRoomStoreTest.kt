package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnifiedPlaybackRoomStoreTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
        NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
    private val song = SongItem(42, "晴天", "周杰伦", "叶惠美", 1, 269_000, null, channelId = "netease", audioId = "42")

    @Test fun uuidResolvesExactNativeRecordingAndKeepsPartsSeparate() = runTest {
        val db = database()
        try {
            val playback = UnifiedPlaybackRoomStore(db)
            val first = playback.identify(song)
            assertEquals("42", playback.load(first.songId)?.song?.audioId)
            val other = playback.identify(song.copy(id = 43, audioId = "43"))
            assertNotEquals(first.songId, other.songId)
            for (cid in listOf("11", "22")) {
                val target = playback.identify(song.copy(id = 10, audioId = "10", subAudioId = cid, channelId = "bilibili"))
                assertEquals(cid, playback.load(target.songId)?.song?.subAudioId)
            }
            assertNull(playback.load("42"))
            assertNull(playback.load("00000000-0000-0000-0000-000000000000"))
            val catalog = UnifiedLibraryRoomStore(db).registerSource(SongSourceKey("kuwo", "42"),
                UnifiedSongMetadata(song.name, song.artist, song.album, song.durationMs))
            assertNull(playback.load(catalog.id))
        } finally { db.close() }
    }

    @Test fun uuidLookupPreservesDurableMetadataAndRejectsWrongTargetDecisions() = runTest {
        val db = database()
        try {
            val playback = UnifiedPlaybackRoomStore(db)
            val target = playback.identify(song)
            val durable = song.copy(customName = "我的歌名", userLyricOffsetMs = 320)
            val identity = durable.identity()
            db.localPlaylistDao().insertTracks(listOf(TrackEntity(durable.stableKey(), identity.id, identity.album,
                identity.mediaUri, durable.id, durable.name, durable.artist, durable.album, durable.albumId,
                durable.durationMs, null, durable.mediaUri, durable.channelId, durable.audioId, durable.subAudioId,
                null, null, null, 1, Gson().toJson(durable.copy(streamUrl = "https://example.test/transient")))))
            val restored = requireNotNull(playback.load(target.songId)).song
            assertEquals(durable.stableKey(), restored.stableKey())
            assertEquals("我的歌名", restored.customName)
            assertEquals(320L, restored.userLyricOffsetMs)
            assertNull(restored.streamUrl)
            val matches = SourceMatchRoomStore(db)
            matches.decide(song, RecordingSourceCandidate(SongSourceKey("bilibili", "10", "11"),
                UnifiedSongMetadata(song.name, song.artist, song.album, song.durationMs)), SourceMatchDecision.CONFIRMED)
            assertEquals(1, matches.matches(target.songId, song).size)
            assertTrue(matches.matches(target.songId, song.copy(id = 99, audioId = "99")).isEmpty())
        } finally { db.close() }
    }
}
