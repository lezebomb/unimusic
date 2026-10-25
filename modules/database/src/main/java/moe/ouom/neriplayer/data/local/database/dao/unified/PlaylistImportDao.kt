package moe.ouom.neriplayer.data.local.database.dao.unified

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistImportJobEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistImportItemEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistImportReceiptEntity

@Dao
interface PlaylistImportDao {
    @Query("SELECT * FROM playlist_import_job WHERE job_id = :id")
    suspend fun job(id: String): PlaylistImportJobEntity?

    @Query("SELECT * FROM playlist_import_job WHERE provider = :provider AND account_key = :account AND remote_playlist_id = :remote")
    suspend fun jobForOrigin(provider: String, account: String, remote: String): PlaylistImportJobEntity?

    @Query("SELECT * FROM playlist_import_job ORDER BY updated_at DESC, job_id")
    fun observeJobs(): Flow<List<PlaylistImportJobEntity>>

    @Insert suspend fun insertJob(job: PlaylistImportJobEntity)
    @Update suspend fun updateJob(job: PlaylistImportJobEntity)

    @Query("UPDATE playlist_import_job SET state = 'RUNNING', run_token = :token, lease_until = :until, updated_at = :now " +
        "WHERE job_id = :id AND state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT') AND retry_at <= :now " +
        "AND (run_token IS NULL OR lease_until <= :now)")
    suspend fun claim(id: String, token: String, now: Long, until: Long): Int

    @Query("SELECT * FROM playlist_import_item WHERE job_id = :id AND state = 'PENDING' ORDER BY position LIMIT :limit")
    suspend fun pending(id: String, limit: Int): List<PlaylistImportItemEntity>

    @Query("SELECT * FROM playlist_import_item WHERE job_id = :id AND state = 'FAILED' ORDER BY position LIMIT :limit OFFSET :offset")
    suspend fun failures(id: String, limit: Int, offset: Int): List<PlaylistImportItemEntity>

    @Query("SELECT COUNT(*) FROM playlist_import_item WHERE job_id = :id AND state = :state")
    suspend fun count(id: String, state: String): Int

    @Insert suspend fun insertItems(items: List<PlaylistImportItemEntity>)
    @Update suspend fun updateItems(items: List<PlaylistImportItemEntity>)
    @Query("DELETE FROM playlist_import_item WHERE job_id = :id") suspend fun clearSnapshot(id: String)

    @Query("UPDATE playlist_import_item SET state = 'PENDING', failure_kind = NULL WHERE job_id = :id AND state = 'FAILED' AND retry_at <= :now")
    suspend fun retryFailures(id: String, now: Long): Int

    @Query("SELECT * FROM playlist_import_receipt WHERE job_id = :id AND resource_id IN (:resources)")
    suspend fun receipts(id: String, resources: List<String>): List<PlaylistImportReceiptEntity>
    @Query("SELECT * FROM playlist_import_receipt WHERE job_id = :id")
    suspend fun allReceipts(id: String): List<PlaylistImportReceiptEntity>
    @Upsert suspend fun saveReceipt(receipt: PlaylistImportReceiptEntity)
}
