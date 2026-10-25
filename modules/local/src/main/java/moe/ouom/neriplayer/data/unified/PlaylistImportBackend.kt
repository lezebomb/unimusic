package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.unified.*
import moe.ouom.neriplayer.platform.netease.api.auth.NeteaseYdDeviceTokenProvider
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.netease.importing.NeteasePlaylistImportProvider

/** Backend API for a future screen/CLI; no Activity, device connection or remote write is required. */
class PlaylistImportBackend(context: Context) {
    private val app = context.applicationContext
    internal val store by lazy {
        PlaylistImportRoomStore(NeriUserDataDatabase.getInstance(app), LocalPlaylistRepository.getInstance(app))
    }

    fun observeJobs() = store.observeJobs()
    suspend fun progress(jobId: String) = store.progress(jobId)
    suspend fun failures(jobId: String, limit: Int = 100, offset: Int = 0) = store.failures(jobId, limit, offset)

    suspend fun listPlaylists(accountKey: String): List<ImportPlaylistSummary> = withNeteaseProvider(app) { provider ->
        val collected = linkedMapOf<String, ImportPlaylistSummary>()
        val cursors = hashSetOf<String?>()
        var cursor: String? = null
        repeat(1000) {
            if (!cursors.add(cursor)) throw PlaylistImportException(ImportFailureKind.INCOMPLETE_RESPONSE)
            val page = provider.listPlaylists(accountKey, cursor)
            val previousCount = collected.size
            page.playlists.forEach { collected.putIfAbsent(it.remoteId, it) }
            if (page.nextCursor == null) return@withNeteaseProvider collected.values.toList()
            if (collected.size == previousCount) throw PlaylistImportException(ImportFailureKind.INCOMPLETE_RESPONSE)
            cursor = page.nextCursor
        }
        throw PlaylistImportException(ImportFailureKind.INCOMPLETE_RESPONSE)
    }

    suspend fun enqueue(accountKey: String, remotePlaylistId: String, mode: PlaylistImportMode = PlaylistImportMode.ONE_SHOT): String {
        val id = store.enqueue(accountKey, remotePlaylistId, mode)
        scheduleQueued(id)
        return id
    }

    suspend fun refresh(jobId: String): Boolean {
        if (!store.refreshLinked(jobId)) return false
        scheduleQueued(jobId)
        return true
    }

    private suspend fun scheduleQueued(id: String) {
        // A queued replacement must not be discarded while an expired/cancelled worker exits.
        val policy = if (store.progress(id)?.state == PlaylistImportState.RUNNING)
            ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        schedule(id, policy)
    }

    suspend fun cancel(jobId: String) {
        store.cancel(jobId) // Invalidate ownership before cancelling the coroutine.
        WorkManager.getInstance(app).cancelUniqueWork(workName(jobId))
    }

    suspend fun retry(jobId: String): Boolean {
        if (!store.retry(jobId)) return false
        schedule(jobId, ExistingWorkPolicy.REPLACE)
        return true
    }

    suspend fun unlink(jobId: String) {
        store.unlink(jobId)
        WorkManager.getInstance(app).cancelUniqueWork(workName(jobId))
    }

    private fun schedule(id: String, policy: ExistingWorkPolicy) {
        WorkManager.getInstance(app).enqueueUniqueWork(workName(id), policy,
            OneTimeWorkRequestBuilder<PlaylistImportWorker>()
                .setInputData(workDataOf("job_id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    private fun workName(id: String) = "unimusic-playlist-import-$id"

    companion object {
        @Volatile private var authRepository: NeteaseCookieRepository? = null

        /** UI login and workers must observe the same session flow, including logout mid-request. */
        fun sharedNeteaseCookies(context: Context): NeteaseCookieRepository = authRepository ?: synchronized(this) {
            authRepository ?: NeteaseCookieRepository(context.applicationContext).also { authRepository = it }
        }
    }
}

internal suspend fun <T> withNeteaseProvider(context: Context, block: suspend (PlaylistImportProvider) -> T): T = coroutineScope {
    val cookies = PlaylistImportBackend.sharedNeteaseCookies(context)
    val client = NeteaseClient { NeteaseYdDeviceTokenProvider(context).getCommentToken() }
    val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
        cookies.cookieFlow.collect { client.setPersistedCookies(it) }
    }
    try { block(NeteasePlaylistImportProvider(client)) }
    finally { monitor.cancel(); client.evictConnections() }
}
