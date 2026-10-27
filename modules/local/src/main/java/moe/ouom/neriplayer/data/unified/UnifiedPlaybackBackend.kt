package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import java.util.UUID
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId

/** A UUID is the logical recording; the compatible SongItem remains the original queue identity. */
data class UnifiedPlaybackTarget(val songId: String, val song: SongItem)

class UnifiedPlaybackBackend(context: Context) {
    private val app = context.applicationContext
    private val store by lazy { UnifiedPlaybackRoomStore(NeriUserDataDatabase.getInstance(app)) }
    suspend fun identify(song: SongItem) = store.identify(song)
    suspend fun load(songId: String) = store.load(songId)
}

/** Reads exact links only. Similar titles never select a different source or merge two UUIDs. */
class UnifiedPlaybackRoomStore(private val db: NeriUserDataDatabase) {
    private val dao = db.unifiedLibraryDao()
    private val gson = Gson()

    suspend fun identify(song: SongItem): UnifiedPlaybackTarget =
        UnifiedPlaybackTarget(SourceMatchRoomStore(db).subjectId(song), song)

    suspend fun load(songId: String): UnifiedPlaybackTarget? = db.withTransaction {
        if (!canonicalUuid(songId)) return@withTransaction null
        val recording = dao.song(songId) ?: return@withTransaction null
        // Resolve current durable user metadata first; do not revive a transient supplied URL.
        for (track in dao.legacyTracksForSong(songId)) {
            val song = runCatching { gson.fromJson(track.durablePayloadJson, SongItem::class.java)?.copy(streamUrl = null) }.getOrNull()
                ?: continue
            if (runCatching { song.stableKey() == track.identityKey }.getOrDefault(false)) {
                return@withTransaction UnifiedPlaybackTarget(songId, song)
            }
        }
        // Search-only recordings may have an exact native source but no legacy track row yet.
        // Local/unknown/catalog sources cannot be reconstructed from an ID or guessed URL.
        val source = dao.sources(songId).firstOrNull { nativeMatchKey(it.key()) && it.provider != "local" }
            ?: return@withTransaction null
        val id = source.resourceId
        val song = when (source.provider) {
            "qqmusic", "kuwo" -> catalogPlaybackSong(moe.ouom.neriplayer.data.model.unified.CatalogTrack(
                source.key(), moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata(recording.title,
                    recording.artist, recording.album, recording.durationMs), null)) ?: return@withTransaction null
            "netease" -> SongItem(id.toLong(), recording.title, recording.artist, recording.album, 0,
                recording.durationMs, null, channelId = "netease", audioId = id)
            "bilibili" -> SongItem(id.toLong(), recording.title, recording.artist, recording.album, 0,
                recording.durationMs, null, channelId = "bilibili", audioId = id, subAudioId = source.subResourceId)
            "youtube_music" -> SongItem(stableYouTubeMusicId(id), recording.title, recording.artist, recording.album, 0,
                recording.durationMs, null, mediaUri = buildYouTubeMusicMediaUri(id), channelId = "youtubeMusic", audioId = id)
            else -> return@withTransaction null
        }
        UnifiedPlaybackTarget(songId, song)
    }
}

internal fun canonicalUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

private fun moe.ouom.neriplayer.data.local.database.entity.unified.SongSourceEntity.key() =
    SongSourceKey(provider, resourceId, subResourceId, recordingContext)
