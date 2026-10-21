package moe.ouom.neriplayer.data.model.unified

/** Ownership is local; a remote origin is an optional refresh relationship. */
data class UnifiedPlaylist(
    val id: String,
    val legacyPlaylistId: Long,
    val name: String,
    val coverUrl: String?,
    val modifiedAt: Long,
    val deleted: Boolean = false
)

data class UnifiedPlaylistMembership(
    val playlistId: String,
    val songId: String,
    val position: Int,
    val addedAt: Long,
    val modifiedAt: Long,
    val deleted: Boolean = false
)

enum class PlaylistImportMode { ONE_SHOT, LINKED }

data class PlaylistOrigin(
    val id: String,
    val playlistId: String,
    val provider: String,
    /** Stable public account ID; never a cookie, token or cookie fingerprint. */
    val accountKey: String,
    val remotePlaylistId: String,
    val mode: PlaylistImportMode,
    val linked: Boolean,
    val importedAt: Long,
    val refreshedAt: Long?,
    val snapshotId: String?
)
