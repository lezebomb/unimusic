package moe.ouom.neriplayer.data.model.unified

enum class PlaylistImportState {
    QUEUED, RUNNING, RETRY_WAIT, PAUSED_AUTH, COMPLETED, PARTIAL_FAILED, CANCELLED, FAILED
}

data class PlaylistImportProgress(
    val jobId: String,
    val provider: String,
    val accountKey: String,
    val remotePlaylistId: String,
    val playlistId: String?,
    val state: PlaylistImportState,
    val total: Int,
    val imported: Int,
    val failed: Int,
    val skipped: Int,
    val lastError: ImportFailureKind?,
    val retryAt: Long,
    val playlistName: String? = null,
    val mode: PlaylistImportMode = PlaylistImportMode.ONE_SHOT,
    val linked: Boolean = false
)

data class PlaylistImportFailure(
    val resourceId: String,
    val position: Int,
    val kind: ImportFailureKind,
    val attempts: Int,
    val retryAt: Long
)
