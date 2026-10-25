package moe.ouom.neriplayer.data.unified

import android.database.sqlite.SQLiteException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.unified.*

enum class PlaylistImportRunResult { FINISHED, RETRY, PAUSED, SUPERSEDED }

/** Network stays outside SQLite transactions; only validated complete batches can advance progress. */
class PlaylistImportCoordinator(
    private val store: PlaylistImportRoomStore,
    private val provider: PlaylistImportProvider,
    private val batchSize: Int = 100
) {
    init { require(batchSize in 1..200) }

    suspend fun run(id: String, maxPages: Int = 20): PlaylistImportRunResult {
        require(maxPages in 1..1000)
        val token = UUID.randomUUID().toString()
        val job = store.claim(id, token) ?: return when (store.progress(id)?.state) {
            PlaylistImportState.QUEUED, PlaylistImportState.RUNNING, PlaylistImportState.RETRY_WAIT -> PlaylistImportRunResult.RETRY
            else -> PlaylistImportRunResult.FINISHED
        }
        val session = provider.sessionIdentity()
        fun checkSession() {
            if (session !== provider.sessionIdentity()) throw PlaylistImportException(ImportFailureKind.ACCOUNT_CHANGED)
        }
        try {
            if (provider.providerId != job.provider) throw PlaylistImportException(ImportFailureKind.INVALID_RESPONSE)
            val snapshot = provider.readSnapshot(job.accountKey, job.remotePlaylistId)
            checkSession()
            store.stageSnapshot(id, token, snapshot)
            repeat(maxPages) {
                currentCoroutineContext().ensureActive()
                store.owned(id, token)
                val page = store.dao.pending(id, batchSize)
                val ids = page.map { it.resourceId }
                val batch = if (ids.isEmpty()) ImportSongBatch(emptyList(), emptyList()) else provider.readSongs(job.accountKey, ids)
                checkSession()
                validateBatch(job.provider, ids, batch)
                if (store.commitPage(id, token, page, batch, ::checkSession)) return PlaylistImportRunResult.FINISHED
            }
            store.release(id, token)
            return PlaylistImportRunResult.RETRY
        } catch (_: ImportRunSuperseded) {
            return PlaylistImportRunResult.SUPERSEDED
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { store.release(id, token) }
            throw cancelled
        } catch (failure: PlaylistImportException) {
            store.release(id, token, failure.kind)
            return if (failure.kind == ImportFailureKind.TEMPORARY) PlaylistImportRunResult.RETRY else PlaylistImportRunResult.PAUSED
        } catch (_: IOException) {
            store.release(id, token, ImportFailureKind.TEMPORARY)
            return PlaylistImportRunResult.RETRY
        } catch (_: SQLiteException) {
            store.release(id, token, ImportFailureKind.LOCAL_STORAGE)
            return PlaylistImportRunResult.PAUSED
        } catch (failure: Exception) {
            store.release(id, token, ImportFailureKind.INTERNAL_ERROR)
            throw failure
        }
    }
}

private fun validateBatch(provider: String, requested: List<String>, batch: ImportSongBatch) {
    val supplied = batch.songs.map { it.source.resourceId } + batch.failures.map { it.resourceId }
    if (supplied.size != supplied.toSet().size || supplied.toSet() != requested.toSet() ||
        batch.songs.any { it.source.provider != provider || it.source.subResourceId.isNotEmpty() || it.source.recordingContext.isNotEmpty() }) {
        throw PlaylistImportException(ImportFailureKind.INCOMPLETE_RESPONSE)
    }
    batch.failures.firstOrNull { it.kind == ImportFailureKind.AUTH_REQUIRED || it.kind == ImportFailureKind.ACCOUNT_CHANGED }
        ?.let { throw PlaylistImportException(it.kind) }
}
