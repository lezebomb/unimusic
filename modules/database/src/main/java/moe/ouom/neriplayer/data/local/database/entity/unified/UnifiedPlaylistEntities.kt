package moe.ouom.neriplayer.data.local.database.entity.unified

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "unified_playlist", indices = [Index(value = ["legacy_playlist_id"], unique = true)])
data class UnifiedPlaylistEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "legacy_playlist_id") val legacyPlaylistId: Long,
    val name: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    val deleted: Boolean
)

@Entity(
    tableName = "unified_playlist_member", primaryKeys = ["playlist_id", "song_id"],
    foreignKeys = [
        ForeignKey(entity = UnifiedPlaylistEntity::class, parentColumns = ["playlist_id"],
            childColumns = ["playlist_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = UnifiedSongEntity::class, parentColumns = ["song_id"],
            childColumns = ["song_id"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["playlist_id", "position"]), Index("song_id")]
)
data class UnifiedPlaylistMemberEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    val deleted: Boolean
)

@Entity(
    tableName = "playlist_origin",
    foreignKeys = [ForeignKey(entity = UnifiedPlaylistEntity::class, parentColumns = ["playlist_id"],
        childColumns = ["playlist_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("playlist_id"), Index(value = ["provider", "account_key", "remote_playlist_id"], unique = true)]
)
data class PlaylistOriginEntity(
    @PrimaryKey @ColumnInfo(name = "origin_id") val originId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val provider: String,
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "remote_playlist_id") val remotePlaylistId: String,
    @ColumnInfo(name = "import_mode") val importMode: String,
    val linked: Boolean,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "refreshed_at") val refreshedAt: Long?,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String?
)
