package moe.ouom.neriplayer.data.local.database.entity.unified

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "song_match", foreignKeys = [ForeignKey(
    entity = UnifiedSongEntity::class, parentColumns = ["song_id"], childColumns = ["song_id"],
    onDelete = ForeignKey.RESTRICT
)], indices = [Index("song_id"), Index(value = ["song_id", "provider", "resource_id", "sub_resource_id", "recording_context"], unique = true)])
data class SongMatchEntity(
    @PrimaryKey @ColumnInfo(name = "match_id") val matchId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    val provider: String,
    @ColumnInfo(name = "resource_id") val resourceId: String,
    @ColumnInfo(name = "sub_resource_id") val subResourceId: String,
    @ColumnInfo(name = "recording_context") val recordingContext: String,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "recording_version") val recordingVersion: String,
    @ColumnInfo(name = "subject_fingerprint") val subjectFingerprint: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: Int,
    @ColumnInfo(name = "observed_at") val observedAt: Long
)

@Entity(tableName = "user_match_override", foreignKeys = [ForeignKey(
    entity = SongMatchEntity::class, parentColumns = ["match_id"], childColumns = ["match_id"],
    onDelete = ForeignKey.RESTRICT
)])
data class UserMatchOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "match_id") val matchId: String,
    val decision: String,
    val revision: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
