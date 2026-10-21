package moe.ouom.neriplayer.data.unified

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.data.local.database.DatabaseOpenException
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase

/** Local additive projection. Never requires network, removes legacy rows or switches UI reads. */
class UnifiedLibraryBackfillWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val store = UnifiedLibraryRoomStore(NeriUserDataDatabase.getInstance(applicationContext))
        var complete = false
        // Bound each worker execution; already committed links are its durable checkpoint.
        for (page in 0 until 32) {
            currentCoroutineContext().ensureActive()
            if (!store.backfillLegacyBatch()) {
                complete = true
                break
            }
            yield()
        }
        if (complete) Result.success() else Result.retry()
    } catch (_: DatabaseOpenException) {
        Result.failure(workDataOf("reason" to "DATABASE_UNAVAILABLE"))
    } catch (_: SQLiteException) {
        Result.retry()
    }

    companion object {
        private const val WORK_NAME = "unimusic-unified-library-backfill"
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UnifiedLibraryBackfillWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
