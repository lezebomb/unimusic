package moe.ouom.neriplayer.data.unified

import java.util.UUID
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistImportItemEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistImportReceiptEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.PlaylistOriginEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UnifiedPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UnifiedPlaylistMemberEntity
import moe.ouom.neriplayer.data.local.playlist.commitImportPage
import moe.ouom.neriplayer.data.local.playlist.nextPlaylistId
import moe.ouom.neriplayer.data.local.playlist.sanitizePlaylistName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.unified.*

/** Returns true only after every snapshot position has a committed result. */
internal suspend fun PlaylistImportRoomStore.commitPage(
    id: String, token: String, page: List<PlaylistImportItemEntity>, batch: ImportSongBatch,
    checkSession: () -> Unit
): Boolean = playlists.commitImportPage { current ->
    val job = owned(id, token)
    checkSession()
    if (dao.pending(id, page.size.coerceAtLeast(1)).map { it.resourceId } != page.map { it.resourceId }) {
        throw ImportRunSuperseded()
    }
    val timestamp = now()
    var origin = library.origin(job.provider, job.accountKey, job.remotePlaylistId)
    var unified = (job.playlistId ?: origin?.playlistId)?.let { library.playlist(it) }
    var target = unified?.let { entry -> current.firstOrNull { it.id == entry.legacyPlaylistId } }
    if ((job.playlistId != null || origin != null) && (target == null || unified?.deleted == true)) {
        throw PlaylistImportException(ImportFailureKind.TARGET_REMOVED)
    }
    val metadata = batch.songs.associateBy { it.source.resourceId }
    val failures = batch.failures.associateBy { it.resourceId }
    val receipts = dao.receipts(id, page.map { it.resourceId }).associateBy { it.resourceId }
    // No empty placeholder playlist when every detail failed. Genuine empty remote playlists work.
    if (target == null && (metadata.isNotEmpty() || job.total == 0)) {
        target = LocalPlaylist(nextPlaylistId(current), sanitizePlaylistName(requireNotNull(job.remoteName)),
            modifiedAt = timestamp, customCoverUrl = job.remoteCoverUrl)
        unified = UnifiedPlaylistEntity(UUID.randomUUID().toString(), target.id, target.name,
            target.customCoverUrl, timestamp, false).also { library.insertPlaylist(it) }
        origin = PlaylistOriginEntity(UUID.randomUUID().toString(), unified.playlistId, job.provider,
            job.accountKey, job.remotePlaylistId, job.importMode, job.importMode == "LINKED", timestamp,
            null, null).also { library.insertOrigin(it) }
    }
    val existingSongs = target?.songs.orEmpty()
    val existingKeys = existingSongs.mapTo(hashSetOf()) { it.stableKey() }
    val updatedSongs = existingSongs.toMutableList()
    val repairingEarlierGap = page.any { it.attemptCount > 0 }
    val orderReceipts = if (repairingEarlierGap) dao.allReceipts(id).associateBy { it.identityKey } else emptyMap()
    val knownOrder = existingSongs.mapNotNull { song -> orderReceipts[song.stableKey()] }
    val repairSourceOrder = repairingEarlierGap && knownOrder.size == existingSongs.size &&
        knownOrder.all { it.snapshotId == job.snapshotId } &&
        knownOrder.map { it.position } == knownOrder.map { it.position }.sorted()
    val firstSnapshot = origin?.snapshotId == null || repairSourceOrder
    var nextAddedAt = (existingSongs.minOfOrNull { it.addedAt } ?: (timestamp + 1)) - 1
    val savedReceipts = ArrayList<PlaylistImportReceiptEntity>()
    val completed = page.map { row ->
        val song = metadata[row.resourceId]
        if (song == null) {
            val failure = requireNotNull(failures[row.resourceId])
            row.copy(state = "FAILED", failureKind = failure.kind.name, attemptCount = row.attemptCount + 1,
                retryAt = timestamp + retryDelay(row.attemptCount + 1))
        } else {
            val item = SongItem(row.resourceId.toLong(), song.metadata.title, song.metadata.artist,
                song.metadata.album, 0, song.metadata.durationMs, song.coverUrl,
                channelId = job.provider, audioId = row.resourceId)
            val key = item.stableKey()
            val unifiedSong = songs.registerSource(song.source, song.metadata, key)
            val unifiedPlaylist = requireNotNull(unified)
            val oldReceipt = receipts[row.resourceId]
            val wasRemoved = oldReceipt?.removed == true ||
                (oldReceipt != null && oldReceipt.identityKey !in existingKeys) ||
                library.member(unifiedPlaylist.playlistId, unifiedSong.id)?.deleted == true
            val receipt = oldReceipt?.copy(removed = wasRemoved) ?: PlaylistImportReceiptEntity(
                id, row.resourceId, unifiedSong.id, key, requireNotNull(job.snapshotId), row.position, wasRemoved)
            dao.saveReceipt(receipt)
            savedReceipts += receipt
            if (wasRemoved) {
                row.copy(state = "SKIPPED", failureKind = null)
            } else {
                if (key !in existingKeys) {
                    val addedAt = if (firstSnapshot) (job.createdAt - row.position).coerceAtLeast(1)
                        else nextAddedAt.coerceAtLeast(1).also { nextAddedAt-- }
                    val stamped = item.copy(addedAt = addedAt,
                        syncMembershipTokens = syncMutationStore.nextSyncCausalTokens(1))
                    updatedSongs += stamped
                    existingKeys += key
                }
                library.insertMembersIfAbsent(listOf(UnifiedPlaylistMemberEntity(unifiedPlaylist.playlistId,
                    unifiedSong.id, updatedSongs.indexOfFirst { it.stableKey() == key },
                    updatedSongs.first { it.stableKey() == key }.addedAt, timestamp, false)))
                row.copy(state = "IMPORTED", failureKind = null, retryAt = 0)
            }
        }
    }
    if (repairSourceOrder) {
        val positions = orderReceipts + savedReceipts.associateBy { it.identityKey }
        updatedSongs.sortBy { positions[it.stableKey()]?.position ?: Int.MAX_VALUE }
        updatedSongs.forEachIndexed { position, song ->
            positions[song.stableKey()]?.let { library.setMemberPosition(requireNotNull(unified).playlistId, it.songId, position) }
        }
    }
    dao.updateItems(completed)
    val remaining = dao.count(id, "PENDING")
    val failed = dao.count(id, "FAILED")
    val done = remaining == 0
    if (done && origin != null) library.refreshOrigin(origin.originId, timestamp, requireNotNull(job.snapshotId))
    dao.updateJob(job.copy(playlistId = unified?.playlistId, importedCount = dao.count(id, "IMPORTED"),
        failedCount = failed, skippedCount = dao.count(id, "SKIPPED"), updatedAt = timestamp,
        state = if (!done) "RUNNING" else if (failed == 0) "COMPLETED" else "PARTIAL_FAILED",
        runToken = if (done) null else token, leaseUntil = if (done) 0 else timestamp + PlaylistImportRoomStore.LEASE_MS,
        retryAt = 0, lastError = null, attemptCount = 0))
    val finalTarget = target
    val next = if (finalTarget == null) current else {
        val changed = finalTarget.copy(songs = updatedSongs,
            modifiedAt = if (updatedSongs == finalTarget.songs) finalTarget.modifiedAt else timestamp)
        if (current.any { it.id == finalTarget.id }) current.map { if (it.id == finalTarget.id) changed else it } else current + changed
    }
    next to done
}
