package moe.ouom.neriplayer.data.unified

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.LegacySongLinkEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.SongSourceEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UnifiedPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UnifiedPlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UnifiedSongEntity
import moe.ouom.neriplayer.data.model.unified.RecordingVersion
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.SourceAvailability
import moe.ouom.neriplayer.data.model.unified.SourcePlaybackMode
import moe.ouom.neriplayer.data.model.unified.UnifiedSong
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata

/**
 * Compatibility projection. It never modifies/deletes legacy rows or grants playback rights.
 * Source registration and legacy links commit together; title similarity is never identity.
 */
class UnifiedLibraryRoomStore(
    private val database: NeriUserDataDatabase,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao = database.unifiedLibraryDao()

    fun observeSongCount(): Flow<Int> = dao.observeSongCount()

    suspend fun songsPage(afterId: String? = null, limit: Int = 100): List<UnifiedSong> {
        requireBatchSize(limit)
        return dao.songsPage(afterId, limit).map { it.toModel() }
    }

    suspend fun songForLegacy(identityKey: String): UnifiedSong? = database.withTransaction {
        dao.sourceForLegacy(identityKey)?.let { dao.song(it.songId)?.toModel() }
    }

    suspend fun registerSource(
        key: SongSourceKey,
        metadata: UnifiedSongMetadata,
        legacyIdentityKey: String? = null,
        deviceOnly: Boolean = key.provider == "local" || key.provider == "legacy"
    ): UnifiedSong = database.withTransaction {
        val source = registerSourceInTransaction(key, metadata, deviceOnly)
        if (legacyIdentityKey != null) linkLegacyInTransaction(legacyIdentityKey, source)
        requireNotNull(dao.song(source.songId)).toModel()
    }

    /** Missing links are the checkpoint. Restarting or inserting an earlier key cannot skip rows. */
    suspend fun backfillLegacySongsPage(limit: Int = 200): Int = database.withTransaction {
        requireBatchSize(limit)
        val tracks = dao.unmappedLegacyPage(limit)
        tracks.forEach { registerLegacyInTransaction(it) }
        tracks.size
    }

    /** One bounded resumable unit of work. False means the current legacy snapshot is projected. */
    suspend fun backfillLegacyBatch(limit: Int = 200): Boolean = database.withTransaction {
        if (backfillLegacySongsPage(limit) > 0) return@withTransaction true
        val playlistId = dao.nextLegacyPlaylistToProject() ?: return@withTransaction false
        backfillLegacyPlaylistPage(playlistId, limit)
        true
    }

    /** Additive projection only; retries neither revive tombstones nor overwrite user edits. */
    suspend fun backfillLegacyPlaylistPage(
        legacyPlaylistId: Long,
        limit: Int = 200
    ): Int = database.withTransaction {
        requireBatchSize(limit)
        val legacy = requireNotNull(database.localPlaylistDao().getPlaylist(legacyPlaylistId))
        val playlist = dao.playlistForLegacy(legacyPlaylistId) ?: UnifiedPlaylistEntity(
            playlistId = UUID.randomUUID().toString(), legacyPlaylistId = legacyPlaylistId,
            name = legacy.name, coverUrl = legacy.customCoverUrl, modifiedAt = legacy.modifiedAt,
            deleted = false
        ).also { dao.insertPlaylist(it) }
        // The missing relationship is the checkpoint, including when an older playlist receives
        // a new member before the previous page. Existing tombstones count as projected rows.
        val members = dao.unmappedLegacyMembersPage(legacyPlaylistId, playlist.playlistId, limit)
        val tracks = database.localPlaylistDao().getTracksByIdentityKeys(members.map { it.identityKey })
            .associateBy { it.identityKey }
        val projected = members.map { member ->
            val source = dao.sourceForLegacy(member.identityKey)
                ?: registerLegacyInTransaction(requireNotNull(tracks[member.identityKey]))
            UnifiedPlaylistMemberEntity(
                playlistId = playlist.playlistId, songId = source.songId,
                position = member.displayPosition, addedAt = member.addedAt,
                modifiedAt = legacy.modifiedAt, deleted = false
            )
        }
        dao.insertMembersIfAbsent(projected)
        members.size
    }

    /** Unlinking never deletes local playlists or their members. */
    suspend fun unlinkOrigin(originId: String) = database.withTransaction { dao.unlinkOrigin(originId) }

    private suspend fun registerLegacyInTransaction(track: TrackEntity): SongSourceEntity {
        dao.sourceForLegacy(track.identityKey)?.let { return it }
        val key = legacyUnifiedSourceKey(track)
        val metadata = UnifiedSongMetadata(track.name, track.artist, track.album, track.durationMs.coerceAtLeast(0))
        val source = registerSourceInTransaction(key, metadata, key.provider == "legacy")
        linkLegacyInTransaction(track.identityKey, source)
        return source
    }

    private suspend fun registerSourceInTransaction(
        key: SongSourceKey,
        metadata: UnifiedSongMetadata,
        deviceOnly: Boolean
    ): SongSourceEntity {
        dao.source(key.provider, key.resourceId, key.subResourceId, key.recordingContext)?.let { return it }
        val timestamp = now()
        val song = UnifiedSongEntity(
            songId = UUID.randomUUID().toString(), title = metadata.title, artist = metadata.artist,
            album = metadata.album, durationMs = metadata.durationMs, recordingVersion = metadata.version.name,
            isrc = metadata.isrc, musicBrainzRecordingId = metadata.musicBrainzRecordingId,
            createdAt = timestamp, modifiedAt = timestamp
        )
        dao.insertSong(song)
        return SongSourceEntity(
            sourceId = UUID.randomUUID().toString(), songId = song.songId,
            provider = key.provider, resourceId = key.resourceId, subResourceId = key.subResourceId,
            recordingContext = key.recordingContext, deviceOnly = deviceOnly,
            playbackMode = SourcePlaybackMode.METADATA_ONLY.name,
            availability = SourceAvailability.UNKNOWN.name, checkedAt = null, permissionExpiresAt = null
        ).also { dao.insertSource(it) }
    }

    private suspend fun linkLegacyInTransaction(identityKey: String, source: SongSourceEntity) {
        require(identityKey.isNotBlank())
        val existing = dao.sourceForLegacy(identityKey)
        if (existing != null) {
            require(existing.sourceId == source.sourceId) { "Legacy identity is already bound to another source" }
        } else {
            dao.insertLegacyLink(LegacySongLinkEntity(identityKey, source.sourceId))
        }
    }
}

private fun requireBatchSize(limit: Int) = require(limit in 1..500) { "Batch size must be between 1 and 500" }

private fun UnifiedSongEntity.toModel(): UnifiedSong = UnifiedSong(
    id = songId, title = title, artist = artist, album = album, durationMs = durationMs,
    version = runCatching { RecordingVersion.valueOf(recordingVersion) }.getOrDefault(RecordingVersion.UNKNOWN),
    isrc = isrc, musicBrainzRecordingId = musicBrainzRecordingId, createdAt = createdAt, modifiedAt = modifiedAt
)
