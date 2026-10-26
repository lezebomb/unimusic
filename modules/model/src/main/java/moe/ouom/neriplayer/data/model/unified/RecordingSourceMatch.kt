package moe.ouom.neriplayer.data.model.unified

enum class SourceMatchDecision { CONFIRMED, REJECTED, NONE }

/** Stable IDs and metadata only. Never contains a signed URL, token or local file path. */
data class RecordingSourceCandidate(val key: SongSourceKey, val metadata: UnifiedSongMetadata)

data class RecordingSourceMatch(
    val id: String,
    val songId: String,
    val candidate: RecordingSourceCandidate,
    val decision: SourceMatchDecision,
    val revision: Long,
    val updatedAt: Long,
    val current: Boolean
)
