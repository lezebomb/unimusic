package moe.ouom.neriplayer.data.unified

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.local.database.DatabaseOpenException

class PlaylistImportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("job_id") ?: return Result.failure()
        return try {
            val backend = PlaylistImportBackend(applicationContext)
            val progress = backend.progress(id) ?: return Result.success()
            val lock = accounts.getOrPut(progress.accountKey) { Mutex() }
            lock.withLock {
                withNeteaseProvider(applicationContext) { provider ->
                    when (PlaylistImportCoordinator(backend.store, provider).run(id)) {
                        PlaylistImportRunResult.RETRY -> Result.retry()
                        else -> Result.success() // Terminal/paused detail is persisted in the job row.
                    }
                }
            }
        } catch (_: DatabaseOpenException) {
            Result.failure()
        } catch (_: SQLiteException) {
            Result.retry()
        }
    }

    companion object {
        // This database only permits the main process; serialize metadata requests per account.
        private val accounts = ConcurrentHashMap<String, Mutex>()
    }
}
