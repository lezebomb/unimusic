package moe.ouom.neriplayer.data.local.database.entity.unified

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "unified_song", indices = [Index("title"), Index("artist")])
data class UnifiedSongEntity(
    @PrimaryKey @ColumnInfo(name = "song_id") val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "recording_version") val recordingVersion: String,
    val isrc: String?,
    @ColumnInfo(name = "musicbrainz_recording_id") val musicBrainzRecordingId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long
)

@Entity(
    tableName = "song_source",
    foreignKeys = [ForeignKey(
        entity = UnifiedSongEntity::class, parentColumns = ["song_id"], childColumns = ["song_id"],
        onDelete = ForeignKey.RESTRICT
    )],
    indices = [
        Index("song_id"),
        Index(value = ["provider", "resource_id", "sub_resource_id", "recording_context"], unique = true)
    ]
)
data class SongSourceEntity(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    val provider: String,
    @ColumnInfo(name = "resource_id") val resourceId: String,
    @ColumnInfo(name = "sub_resource_id") val subResourceId: String,
    @ColumnInfo(name = "recording_context") val recordingContext: String,
    @ColumnInfo(name = "device_only") val deviceOnly: Boolean,
    @ColumnInfo(name = "playback_mode") val playbackMode: String,
    val availability: String,
    @ColumnInfo(name = "checked_at") val checkedAt: Long?,
    @ColumnInfo(name = "permission_expires_at") val permissionExpiresAt: Long?
)

/** No FK to legacy track: old repository cleanup must retain its existing behavior. */
@Entity(
    tableName = "legacy_song_link",
    foreignKeys = [ForeignKey(
        entity = SongSourceEntity::class, parentColumns = ["source_id"], childColumns = ["source_id"],
        onDelete = ForeignKey.RESTRICT
    )],
    indices = [Index("source_id")]
)
data class LegacySongLinkEntity(
    @PrimaryKey @ColumnInfo(name = "identity_key") val identityKey: String,
    @ColumnInfo(name = "source_id") val sourceId: String
)
