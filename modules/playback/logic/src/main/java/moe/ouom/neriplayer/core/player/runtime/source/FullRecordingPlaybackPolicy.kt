package moe.ouom.neriplayer.core.player.runtime.source

import moe.ouom.neriplayer.data.model.playback.SongUrlResult

/** Adapters validate recording identity and duration; known previews never enter normal playback. */
fun SongUrlResult.Success.fullRecordingPlaybackResultOrNull(): SongUrlResult.Success? {
    if (isPreviewClip || audioInfo?.isPreviewClip == true) return null
    val fullFallbacks = fallbackCandidates.filterNot { candidate ->
        candidate.isPreviewClip || candidate.audioInfo?.isPreviewClip == true
    }
    return if (fullFallbacks.size == fallbackCandidates.size) this
    else copy(fallbackCandidates = fullFallbacks)
}
