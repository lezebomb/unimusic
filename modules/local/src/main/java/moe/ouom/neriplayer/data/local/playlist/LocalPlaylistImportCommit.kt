package moe.ouom.neriplayer.data.local.playlist

import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.unified.ImportFailureKind
import moe.ouom.neriplayer.data.model.unified.PlaylistImportException

/** Serialize with all existing user edits. Imports may never fall back to a separate JSON write. */
internal suspend fun <T> LocalPlaylistRepository.commitImportPage(
    prepare: suspend LocalPlaylistRepository.(List<LocalPlaylist>) -> Pair<List<LocalPlaylist>, T>
): T = commitPlaylistMutation {
    val activeStore = roomStore
    if (!roomStorageEnabled || activeStore == null || !activeStore.isRoomPrimary()) {
        throw PlaylistImportException(ImportFailureKind.LOCAL_STORAGE)
    }
    val previous = _playlists.value
    flushPendingSyncMutation(LocalPlaylistRoomStore.domainDigest(previous))
    currentCoroutineContext().ensureActive()
    // A bounded page finishes publication even if WorkManager stops us just after SQLite commits.
    val prepared = withContext(NonCancellable) {
        syncMutationStore.markSyncMutation()
        val committed = activeStore.commitImport(previous) {
            val result = prepare(previous)
            normalizePlaylistOrder(result.first) to result.second
        }
        _playlists.value = committed.first
        _playlistCount.value = committed.first.size
        _syncMutationPending.value = true
        committed
    }
    try {
        val outbox = activeStore.readPendingSyncMutationOutbox()
        if (outbox != null) settlePendingSyncMutation(outbox, triggerSync = true)
    } catch (failure: IOException) {
        // A durable outbox remains available to the existing startup/sync recovery path.
        NPLogger.w("PlaylistImport", "Page committed; sync scheduling will be retried", failure)
    }
    prepared.second
}
