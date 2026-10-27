package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.local.database.DatabaseOpenException
import moe.ouom.neriplayer.data.model.unified.PlaylistImportMode
import moe.ouom.neriplayer.data.model.unified.PlaylistImportProgress
import moe.ouom.neriplayer.data.model.unified.PlaylistImportState

// Deliberately device-only, outside settings backup/sync. Corruption disables new automatic work.
private val Context.importRefreshPreferences by preferencesDataStore(
    name = "unimusic_import_refresh",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

internal enum class AutomaticImportAction { NONE, REFRESH, RESUME }

internal fun automaticImportAction(job: PlaylistImportProgress): AutomaticImportAction {
    if (job.provider != "netease" || !job.linked || job.mode != PlaylistImportMode.LINKED) return AutomaticImportAction.NONE
    return when (job.state) {
        PlaylistImportState.COMPLETED -> AutomaticImportAction.REFRESH
        PlaylistImportState.QUEUED, PlaylistImportState.RETRY_WAIT -> AutomaticImportAction.RESUME
        else -> AutomaticImportAction.NONE // Cancelled, paused or failed jobs require an explicit user action.
    }
}

class PlaylistImportAutoRefresh(context: Context) {
    private val app = context.applicationContext
    val enabled = app.importRefreshPreferences.data.map { it[ENABLED] ?: false }
        .catch { failure -> if (failure is IOException) emit(false) else throw failure }
        .distinctUntilChanged()

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        app.importRefreshPreferences.edit { it[ENABLED] = value }
        reconcile()
    }

    suspend fun reconcile() {
        val manager = WorkManager.getInstance(app)
        val operation = if (isEnabled()) {
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PlaylistImportAutoRefreshWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(24, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .addTag(WORK_NAME).build())
        } else manager.cancelUniqueWork(WORK_NAME)
        awaitImportScheduling(operation)
    }

    companion object {
        const val WORK_NAME = "unimusic-linked-playlist-daily-refresh"
        private val ENABLED = booleanPreferencesKey("enabled")
    }
}

internal suspend fun awaitImportScheduling(operation: Operation) = withTimeout(15_000L) {
    runInterruptible(Dispatchers.IO) { operation.result.get() }
}

/** Only schedules the existing import pipeline; no platform credentials or remote writes enter WorkData. */
class PlaylistImportAutoRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val preferences = PlaylistImportAutoRefresh(applicationContext)
        return try {
            if (!preferences.isEnabled()) return Result.success()
            val cookies = PlaylistImportBackend.sharedNeteaseCookies(applicationContext)
            val session = cookies.cookieFlow.value["MUSIC_U"]?.takeIf(String::isNotBlank) ?: return Result.success()
            val backend = PlaylistImportBackend(applicationContext)
            for (job in backend.observeJobs().first()) {
                if (!preferences.isEnabled() || cookies.cookieFlow.value["MUSIC_U"] != session) break
                when (automaticImportAction(job)) {
                    AutomaticImportAction.REFRESH -> backend.refresh(job.jobId, automatic = true)
                    AutomaticImportAction.RESUME -> backend.resumeAutomatic(job.jobId)
                    AutomaticImportAction.NONE -> Unit
                }
            }
            Result.success()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: DatabaseOpenException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
