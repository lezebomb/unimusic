package moe.ouom.neriplayer.data.local.database.entity.unified

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlist_import_job", indices = [
    Index(value = ["provider", "account_key", "remote_playlist_id"], unique = true)
])
data class PlaylistImportJobEntity(
    @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
    val provider: String,
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "remote_playlist_id") val remotePlaylistId: String,
    @ColumnInfo(name = "import_mode") val importMode: String,
    val state: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String? = null,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String? = null,
    @ColumnInfo(name = "remote_name") val remoteName: String? = null,
    @ColumnInfo(name = "remote_cover_url") val remoteCoverUrl: String? = null,
    val total: Int = 0,
    @ColumnInfo(name = "imported_count") val importedCount: Int = 0,
    @ColumnInfo(name = "failed_count") val failedCount: Int = 0,
    @ColumnInfo(name = "skipped_count") val skippedCount: Int = 0,
    @ColumnInfo(name = "run_token") val runToken: String? = null,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long = 0,
    @ColumnInfo(name = "retry_at") val retryAt: Long = 0,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(tableName = "playlist_import_item", primaryKeys = ["job_id", "position"],
    foreignKeys = [ForeignKey(entity = PlaylistImportJobEntity::class, parentColumns = ["job_id"],
        childColumns = ["job_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["job_id", "resource_id"], unique = true), Index(value = ["job_id", "state", "position"])])
data class PlaylistImportItemEntity(
    @ColumnInfo(name = "job_id") val jobId: String,
    val position: Int,
    @ColumnInfo(name = "resource_id") val resourceId: String,
    val state: String = "PENDING",
    @ColumnInfo(name = "failure_kind") val failureKind: String? = null,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "retry_at") val retryAt: Long = 0
)

/** Survives snapshot replacement: absence from the local playlist after import is a user deletion. */
@Entity(tableName = "playlist_import_receipt", primaryKeys = ["job_id", "resource_id"],
    foreignKeys = [ForeignKey(entity = PlaylistImportJobEntity::class, parentColumns = ["job_id"],
        childColumns = ["job_id"], onDelete = ForeignKey.CASCADE)])
data class PlaylistImportReceiptEntity(
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "resource_id") val resourceId: String,
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "identity_key") val identityKey: String,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    val position: Int,
    val removed: Boolean = false
)
