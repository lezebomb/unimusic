package moe.ouom.neriplayer.core.player.policy.progress

const val LONG_FORM_PLAYBACK_MIN_DURATION_MS = 15L * 60L * 1000L
private const val LONG_FORM_PLAYBACK_MIN_RESUME_POSITION_MS = 5L * 1000L
private const val LONG_FORM_PLAYBACK_COMPLETION_TOLERANCE_MS = 30L * 1000L

fun resolveLongFormPlaybackResumePosition(
    enabled: Boolean,
    durationMs: Long,
    requestedPositionMs: Long,
    rememberedPositionMs: Long,
    allowRememberedPosition: Boolean = true
): Long {
    val normalizedRequestedPositionMs = requestedPositionMs.coerceAtLeast(0L)
    if (normalizedRequestedPositionMs > 0L) {
        return normalizedRequestedPositionMs
    }
    if (!allowRememberedPosition || !enabled || durationMs < LONG_FORM_PLAYBACK_MIN_DURATION_MS) {
        return 0L
    }

    val normalizedRememberedPositionMs = rememberedPositionMs.coerceAtLeast(0L)
    val latestResumePositionMs =
        (durationMs - LONG_FORM_PLAYBACK_COMPLETION_TOLERANCE_MS).coerceAtLeast(0L)
    return normalizedRememberedPositionMs.takeIf {
        it >= LONG_FORM_PLAYBACK_MIN_RESUME_POSITION_MS &&
            it < latestResumePositionMs
    } ?: 0L
}

fun resolveLongFormPlaybackPositionForPersistence(
    enabled: Boolean,
    durationMs: Long,
    positionMs: Long
): Long? {
    if (!enabled || durationMs < LONG_FORM_PLAYBACK_MIN_DURATION_MS) {
        return null
    }

    val normalizedPositionMs = positionMs.coerceAtLeast(0L)
    val latestResumePositionMs =
        (durationMs - LONG_FORM_PLAYBACK_COMPLETION_TOLERANCE_MS).coerceAtLeast(0L)
    if (normalizedPositionMs >= latestResumePositionMs) {
        return 0L
    }
    return normalizedPositionMs.takeIf {
        it >= LONG_FORM_PLAYBACK_MIN_RESUME_POSITION_MS
    }
}

/** A full-recording bookmark is outside a preview timeline; retain only proven in-clip positions. */
fun resolvePreviewMediaStartPosition(requestedPositionMs: Long, isPreview: Boolean, previewDurationMs: Long?): Long {
    val position = requestedPositionMs.coerceAtLeast(0L)
    if (!isPreview) return position
    val duration = previewDurationMs?.takeIf { it > 0L } ?: return 0L
    return position.takeIf { it < duration } ?: 0L
}
