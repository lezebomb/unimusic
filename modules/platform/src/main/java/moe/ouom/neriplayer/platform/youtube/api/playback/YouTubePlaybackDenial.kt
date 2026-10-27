package moe.ouom.neriplayer.platform.youtube.api.playback

import java.io.IOException

/** Explicit platform decisions, without response bodies, account identifiers or media addresses. */
enum class YouTubePlaybackDenialReason { LOGIN_REQUIRED, UNPLAYABLE }

class YouTubePlaybackDeniedException(val reason: YouTubePlaybackDenialReason) :
    IOException("YouTube playback rejected: ${reason.name}") {
    val requiresLogin: Boolean get() = reason == YouTubePlaybackDenialReason.LOGIN_REQUIRED
}

/** Keep every normal client fallback, but do not repeat a completed anonymous denial sweep. */
internal fun terminalAnonymousPlaybackDenial(
    statuses: Set<String>,
    hasLoginCookies: Boolean,
    sawOk: Boolean,
    sawRequestFailure: Boolean
): YouTubePlaybackDenialReason? {
    if (hasLoginCookies || sawOk || sawRequestFailure || statuses.isEmpty()) return null
    val terminal = setOf("LOGIN_REQUIRED", "UNPLAYABLE", "AGE_CHECK_REQUIRED", "CONTENT_CHECK_REQUIRED", "LIVE_STREAM_OFFLINE")
    if (statuses.any { it !in terminal }) return null
    return if (statuses.any { it == "LOGIN_REQUIRED" || it == "AGE_CHECK_REQUIRED" || it == "CONTENT_CHECK_REQUIRED" }) {
        YouTubePlaybackDenialReason.LOGIN_REQUIRED
    } else YouTubePlaybackDenialReason.UNPLAYABLE
}
