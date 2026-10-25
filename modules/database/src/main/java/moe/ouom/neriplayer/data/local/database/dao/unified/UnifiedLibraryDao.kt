package moe.ouom.neriplayer.data.local.database.dao.unified

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.LegacySongLinkEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistOriginEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.SongSourceEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UnifiedPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UnifiedPlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UnifiedSongEntity

@Dao
interface UnifiedLibraryDao {
    @Query("SELECT * FROM unified_song WHERE song_id = :id")
    suspend fun song(id: String): UnifiedSongEntity?

    @Query("SELECT * FROM unified_song WHERE (:afterId IS NULL OR song_id > :afterId) ORDER BY song_id LIMIT :limit")
    suspend fun songsPage(afterId: String?, limit: Int): List<UnifiedSongEntity>

    @Query("SELECT COUNT(*) FROM unified_song")
    fun observeSongCount(): Flow<Int>

    @Query("SELECT * FROM song_source WHERE provider = :provider AND resource_id = :resourceId " +
        "AND sub_resource_id = :subResourceId AND recording_context = :context LIMIT 1")
    suspend fun source(provider: String, resourceId: String, subResourceId: String, context: String): SongSourceEntity?

    @Query("SELECT * FROM song_source WHERE song_id = :songId ORDER BY source_id")
    suspend fun sources(songId: String): List<SongSourceEntity>

    @Query("SELECT source.* FROM song_source AS source JOIN legacy_song_link AS link " +
        "ON link.source_id = source.source_id WHERE link.identity_key = :key")
    suspend fun sourceForLegacy(key: String): SongSourceEntity?

    @Query("SELECT track.* FROM track LEFT JOIN legacy_song_link AS link " +
        "ON track.identity_key = link.identity_key WHERE link.identity_key IS NULL " +
        "ORDER BY track.identity_key LIMIT :limit")
    suspend fun unmappedLegacyPage(limit: Int): List<TrackEntity>

    @Query("SELECT legacy.* FROM playlist_member AS legacy " +
        "LEFT JOIN legacy_song_link AS link ON legacy.identity_key = link.identity_key " +
        "LEFT JOIN song_source AS source ON link.source_id = source.source_id " +
        "LEFT JOIN unified_playlist_member AS member ON member.song_id = source.song_id " +
        "AND member.playlist_id = :unifiedPlaylistId " +
        "WHERE legacy.playlist_id = :playlistId AND member.song_id IS NULL " +
        "ORDER BY legacy.display_position, legacy.identity_key LIMIT :limit")
    suspend fun unmappedLegacyMembersPage(playlistId: Long, unifiedPlaylistId: String, limit: Int): List<PlaylistMemberEntity>

    @Query("SELECT legacy.playlist_id FROM local_playlist AS legacy " +
        "LEFT JOIN unified_playlist AS playlist ON playlist.legacy_playlist_id = legacy.playlist_id " +
        "WHERE playlist.playlist_id IS NULL OR EXISTS (" +
        "SELECT 1 FROM playlist_member AS old_member " +
        "LEFT JOIN legacy_song_link AS link ON old_member.identity_key = link.identity_key " +
        "LEFT JOIN song_source AS source ON source.source_id = link.source_id " +
        "LEFT JOIN unified_playlist_member AS member ON member.playlist_id = playlist.playlist_id " +
        "AND member.song_id = source.song_id " +
        "WHERE old_member.playlist_id = legacy.playlist_id AND member.song_id IS NULL) " +
        "ORDER BY legacy.playlist_id LIMIT 1")
    suspend fun nextLegacyPlaylistToProject(): Long?

    @Insert suspend fun insertSong(song: UnifiedSongEntity)
    @Insert suspend fun insertSource(source: SongSourceEntity)
    @Insert suspend fun insertLegacyLink(link: LegacySongLinkEntity)

    @Query("SELECT * FROM unified_playlist WHERE legacy_playlist_id = :legacyId")
    suspend fun playlistForLegacy(legacyId: Long): UnifiedPlaylistEntity?

    @Query("SELECT * FROM unified_playlist WHERE deleted = 0 ORDER BY modified_at DESC, playlist_id")
    fun observePlaylists(): Flow<List<UnifiedPlaylistEntity>>

    @Insert suspend fun insertPlaylist(playlist: UnifiedPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembersIfAbsent(members: List<UnifiedPlaylistMemberEntity>)

    @Query("SELECT * FROM unified_playlist_member WHERE playlist_id = :playlistId " +
        "AND deleted = 0 ORDER BY position, song_id LIMIT :limit OFFSET :offset")
    suspend fun membersPage(playlistId: String, limit: Int, offset: Int): List<UnifiedPlaylistMemberEntity>

    @Insert suspend fun insertOrigin(origin: PlaylistOriginEntity)

    @Query("SELECT * FROM unified_playlist WHERE playlist_id = :id")
    suspend fun playlist(id: String): UnifiedPlaylistEntity?

    @Query("SELECT * FROM unified_playlist_member WHERE playlist_id = :playlistId AND song_id = :songId")
    suspend fun member(playlistId: String, songId: String): UnifiedPlaylistMemberEntity?

    @Query("UPDATE playlist_origin SET refreshed_at = :now, snapshot_id = :snapshot WHERE origin_id = :id")
    suspend fun refreshOrigin(id: String, now: Long, snapshot: String)

    @Query("UPDATE playlist_origin SET import_mode = :mode, linked = :linked WHERE origin_id = :id")
    suspend fun setOriginMode(id: String, mode: String, linked: Boolean)

    @Query("UPDATE unified_playlist_member SET position = :position WHERE playlist_id = :playlist AND song_id = :song AND deleted = 0")
    suspend fun setMemberPosition(playlist: String, song: String, position: Int)

    @Query("SELECT * FROM playlist_origin WHERE provider = :provider AND account_key = :accountKey " +
        "AND remote_playlist_id = :remoteId")
    suspend fun origin(provider: String, accountKey: String, remoteId: String): PlaylistOriginEntity?

    @Query("UPDATE playlist_origin SET linked = 0 WHERE origin_id = :originId")
    suspend fun unlinkOrigin(originId: String)
}
