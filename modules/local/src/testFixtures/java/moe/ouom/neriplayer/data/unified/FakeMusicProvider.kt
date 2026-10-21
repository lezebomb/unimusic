package moe.ouom.neriplayer.data.unified

import moe.ouom.neriplayer.data.model.unified.ImportFailureKind
import moe.ouom.neriplayer.data.model.unified.ImportPlaylistPage
import moe.ouom.neriplayer.data.model.unified.ImportPlaylistSnapshot
import moe.ouom.neriplayer.data.model.unified.ImportSong
import moe.ouom.neriplayer.data.model.unified.ImportSongBatch
import moe.ouom.neriplayer.data.model.unified.ImportSongFailure
import moe.ouom.neriplayer.data.model.unified.PlaylistImportException
import moe.ouom.neriplayer.data.model.unified.PlaylistImportProvider

/** Shared deterministic provider for coordinator/Room/UI tests; no real platform account needed. */
class FakeMusicProvider(
    override val providerId: String = "fake",
    var activeAccountKey: String? = "test-account",
    val snapshots: MutableMap<String, ImportPlaylistSnapshot> = mutableMapOf(),
    val songs: MutableMap<String, ImportSong> = mutableMapOf(),
    /** Inject delays, cancellation, transient failures or account changes at request boundaries. */
    var beforeRequest: suspend (String) -> Unit = {}
) : PlaylistImportProvider {
    val requestedBatches = mutableListOf<List<String>>()
    val songFailures = mutableMapOf<String, ImportFailureKind>()

    override suspend fun listPlaylists(accountKey: String, cursor: String?, limit: Int): ImportPlaylistPage {
        require(limit in 1..100)
        checkAccount(accountKey, "catalog")
        val offset = cursor?.toInt() ?: 0
        require(offset >= 0)
        val catalog = snapshots.values.filter { it.accountKey == accountKey }.map { it.playlist }.sortedBy { it.remoteId }
        return ImportPlaylistPage(catalog.drop(offset).take(limit),
            if (offset + limit < catalog.size) (offset + limit).toString() else null)
    }

    override suspend fun readSnapshot(accountKey: String, remotePlaylistId: String): ImportPlaylistSnapshot {
        checkAccount(accountKey, "snapshot")
        return snapshots[remotePlaylistId]?.takeIf { it.accountKey == accountKey }
            ?: throw PlaylistImportException(ImportFailureKind.NOT_FOUND)
    }

    override suspend fun readSongs(accountKey: String, resourceIds: List<String>): ImportSongBatch {
        require(resourceIds.size in 1..200)
        checkAccount(accountKey, "songs")
        requestedBatches += resourceIds.toList()
        val ids = resourceIds.distinct()
        return ImportSongBatch(ids.filterNot(songFailures::containsKey).mapNotNull(songs::get),
            ids.filter { it in songFailures || it !in songs }.map {
                ImportSongFailure(it, songFailures[it] ?: ImportFailureKind.NOT_FOUND)
            })
    }

    private suspend fun checkAccount(expected: String, operation: String) {
        beforeRequest(operation)
        if (activeAccountKey == null) throw PlaylistImportException(ImportFailureKind.AUTH_REQUIRED)
        if (activeAccountKey != expected) throw PlaylistImportException(ImportFailureKind.ACCOUNT_CHANGED)
    }
}
