package moe.ouom.neriplayer.data.unified

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.map
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistImportItemEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistImportJobEntity
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.unified.*

internal class ImportRunSuperseded : Exception()

class PlaylistImportRoomStore(
    internal val database: NeriUserDataDatabase,
    internal val playlists: LocalPlaylistRepository,
    internal val now: () -> Long = System::currentTimeMillis
) {
    internal val dao = database.playlistImportDao()
    internal val library = database.unifiedLibraryDao()
    internal val songs = UnifiedLibraryRoomStore(database, now)

    fun observeJobs() = kotlinx.coroutines.flow.combine(dao.observeJobs(), library.observeOrigins()) { jobs, origins ->
        val byKey = origins.associateBy { Triple(it.provider, it.accountKey, it.remotePlaylistId) }
        jobs.map { job -> job.progress(byKey[Triple(job.provider, job.accountKey, job.remotePlaylistId)]) }
    }
    suspend fun progress(id: String): PlaylistImportProgress? = dao.job(id)?.let { job ->
        job.progress(library.origin(job.provider, job.accountKey, job.remotePlaylistId))
    }

    suspend fun failures(id: String, limit: Int = 100, offset: Int = 0): List<PlaylistImportFailure> {
        require(limit in 1..200 && offset >= 0)
        return dao.failures(id, limit, offset).map {
            PlaylistImportFailure(it.resourceId, it.position, ImportFailureKind.valueOf(requireNotNull(it.failureKind)),
                it.attemptCount, it.retryAt)
        }
    }

    suspend fun enqueue(accountKey: String, remoteId: String, mode: PlaylistImportMode): String = database.withTransaction {
        require(accountKey.toLongOrNull()?.let { it > 0 && it.toString() == accountKey } == true)
        require(remoteId.toLongOrNull()?.let { it > 0 && it.toString() == remoteId } == true)
        val timestamp = now()
        val existing = dao.jobForOrigin("netease", accountKey, remoteId)
        if (existing != null) {
            if (existing.runToken != null && existing.leaseUntil > timestamp) return@withTransaction existing.jobId
            dao.updateJob(existing.copy(state = "QUEUED", importMode = mode.name, runToken = null,
                leaseUntil = 0, retryAt = 0, lastError = null, attemptCount = 0, updatedAt = timestamp))
            library.origin("netease", accountKey, remoteId)?.let {
                library.setOriginMode(it.originId, mode.name, mode == PlaylistImportMode.LINKED)
            }
            existing.jobId
        } else {
            val job = PlaylistImportJobEntity(UUID.randomUUID().toString(), "netease", accountKey, remoteId,
                mode.name, "QUEUED", createdAt = timestamp, updatedAt = timestamp)
            dao.insertJob(job)
            job.jobId
        }
    }

    internal suspend fun claim(id: String, token: String): PlaylistImportJobEntity? = database.withTransaction {
        val timestamp = now()
        if (dao.claim(id, token, timestamp, timestamp + LEASE_MS) == 0) null else dao.job(id)
    }

    internal suspend fun owned(id: String, token: String): PlaylistImportJobEntity {
        val job = dao.job(id) ?: throw ImportRunSuperseded()
        if (job.runToken != token || job.state != "RUNNING") throw ImportRunSuperseded()
        return job
    }

    internal suspend fun stageSnapshot(id: String, token: String, snapshot: ImportPlaylistSnapshot) = database.withTransaction {
        val job = owned(id, token)
        if (snapshot.providerId != job.provider || snapshot.accountKey != job.accountKey ||
            snapshot.playlist.remoteId != job.remotePlaylistId || snapshot.snapshotId.isBlank() ||
            snapshot.playlist.trackCount != snapshot.resourceIds.size || snapshot.resourceIds.size > 100_000 ||
            snapshot.playlist.name.isBlank() || snapshot.resourceIds.any { it.toLongOrNull()?.let { n -> n > 0 && n.toString() == it } != true }) {
            throw PlaylistImportException(ImportFailureKind.INVALID_RESPONSE)
        }
        val ids = snapshot.resourceIds.distinct()
        if (job.snapshotId == snapshot.snapshotId) {
            if (job.total != ids.size) throw PlaylistImportException(ImportFailureKind.INVALID_RESPONSE)
        } else {
            dao.clearSnapshot(id)
            snapshot.resourceIds.withIndex().distinctBy { it.value }
                .map { PlaylistImportItemEntity(id, it.index, it.value) }
                .chunked(500).forEach { dao.insertItems(it) }
        }
        dao.updateJob(job.copy(snapshotId = snapshot.snapshotId, remoteName = snapshot.playlist.name,
            remoteCoverUrl = snapshot.playlist.coverUrl, total = ids.size,
            importedCount = dao.count(id, "IMPORTED"), failedCount = dao.count(id, "FAILED"),
            skippedCount = dao.count(id, "SKIPPED"), updatedAt = now(), leaseUntil = now() + LEASE_MS))
    }

    suspend fun cancel(id: String) = database.withTransaction {
        dao.job(id)?.let { dao.updateJob(it.copy(state = "CANCELLED", runToken = null, leaseUntil = 0, updatedAt = now())) }
    }

    suspend fun unlink(id: String) = database.withTransaction {
        val job = dao.job(id) ?: return@withTransaction
        library.origin(job.provider, job.accountKey, job.remotePlaylistId)?.let { library.unlinkOrigin(it.originId) }
        cancel(id)
    }

    suspend fun refreshLinked(id: String): Boolean = database.withTransaction {
        val job = dao.job(id) ?: return@withTransaction false
        val origin = library.origin(job.provider, job.accountKey, job.remotePlaylistId)
        if (origin?.linked != true || origin.importMode != "LINKED") return@withTransaction false
        enqueue(job.accountKey, job.remotePlaylistId, PlaylistImportMode.LINKED)
        true
    }

    /** Failed rows retain positions and attempt counts; backoff is not bypassed by repeated taps. */
    suspend fun retry(id: String): Boolean = database.withTransaction {
        val job = dao.job(id) ?: return@withTransaction false
        if (job.state == "RUNNING" && job.leaseUntil > now()) return@withTransaction false
        val reset = dao.retryFailures(id, now())
        if (reset == 0 && job.state == "PARTIAL_FAILED") return@withTransaction false
        if (job.state == "COMPLETED") return@withTransaction false
        dao.updateJob(job.copy(state = "QUEUED", runToken = null, leaseUntil = 0, retryAt = 0,
            failedCount = dao.count(id, "FAILED"), lastError = null, updatedAt = now()))
        true
    }

    internal suspend fun release(id: String, token: String, error: ImportFailureKind? = null) = database.withTransaction {
        val job = dao.job(id) ?: return@withTransaction
        if (job.runToken != token || job.state != "RUNNING") return@withTransaction
        val state = when (error) {
            null -> "QUEUED"
            ImportFailureKind.AUTH_REQUIRED, ImportFailureKind.ACCOUNT_CHANGED -> "PAUSED_AUTH"
            ImportFailureKind.TEMPORARY -> "RETRY_WAIT"
            ImportFailureKind.TARGET_REMOVED -> "CANCELLED"
            else -> "FAILED"
        }
        dao.updateJob(job.copy(state = state, runToken = null, leaseUntil = 0, lastError = error?.name,
            attemptCount = job.attemptCount + if (error == null) 0 else 1,
            retryAt = if (error == ImportFailureKind.TEMPORARY) now() + retryDelay(job.attemptCount + 1) else 0,
            updatedAt = now()))
    }

    internal companion object { const val LEASE_MS = 120_000L }
}

internal fun retryDelay(attempt: Int): Long = 30_000L * (1L shl (attempt - 1).coerceIn(0, 6))

private fun PlaylistImportJobEntity.progress(origin: moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistOriginEntity?) = PlaylistImportProgress(jobId, provider, accountKey, remotePlaylistId,
    playlistId, PlaylistImportState.valueOf(state), total, importedCount, failedCount, skippedCount,
    lastError?.let(ImportFailureKind::valueOf), retryAt, remoteName,
    PlaylistImportMode.valueOf(importMode), origin?.linked == true)
