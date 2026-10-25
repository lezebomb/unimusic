package moe.ouom.neriplayer.data.model.unified

/** Inbound metadata only. Implementations must never resolve audio or mutate remote libraries. */
interface PlaylistImportProvider {
    val providerId: String
    /** Opaque in-memory login generation. Never persist or serialize this object. */
    fun sessionIdentity(): Any
    suspend fun listPlaylists(accountKey: String, cursor: String? = null, limit: Int = 50): ImportPlaylistPage
    suspend fun readSnapshot(accountKey: String, remotePlaylistId: String): ImportPlaylistSnapshot
    suspend fun readSongs(accountKey: String, resourceIds: List<String>): ImportSongBatch
}

data class ImportPlaylistSummary(
    val remoteId: String,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int
)

data class ImportPlaylistPage(val playlists: List<ImportPlaylistSummary>, val nextCursor: String?)

/** A complete ordered ID snapshot; detail requests may still report individual missing songs. */
data class ImportPlaylistSnapshot(
    val providerId: String,
    val accountKey: String,
    val playlist: ImportPlaylistSummary,
    val snapshotId: String,
    val resourceIds: List<String>
)

data class ImportSong(val source: SongSourceKey, val metadata: UnifiedSongMetadata, val coverUrl: String?)

enum class ImportFailureKind {
    AUTH_REQUIRED, ACCOUNT_CHANGED, ACCESS_DENIED, NOT_FOUND, INCOMPLETE_RESPONSE, INVALID_RESPONSE, TEMPORARY,
    LOCAL_STORAGE, TARGET_REMOVED, INTERNAL_ERROR
}

data class ImportSongFailure(val resourceId: String, val kind: ImportFailureKind)

/** Every requested ID must be present in songs or failures, never silently dropped. */
data class ImportSongBatch(val songs: List<ImportSong>, val failures: List<ImportSongFailure>)

class PlaylistImportException(val kind: ImportFailureKind) : Exception("Playlist import: ${kind.name}")
