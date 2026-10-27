package moe.ouom.neriplayer.data.model.stats

enum class PlaybackFeedbackKind { SKIP, COMPLETE, REPEAT }

/** Observed playback evidence, not a claim that the user likes or dislikes a recording. */
data class PlaybackFeedbackEvent(
    val eventId: String,
    val kind: PlaybackFeedbackKind,
    val occurredAt: Long,
    val heardMs: Long,
    val durationMs: Long
)
