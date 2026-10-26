package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.network.isPlaybackNetworkUsable
import moe.ouom.neriplayer.core.player.policy.source.SourceAvailabilityChecker
import moe.ouom.neriplayer.core.player.policy.source.SourceAttempt
import moe.ouom.neriplayer.core.player.resolver.netease.recordingMetadata
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshSideEffectGate
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.*
import moe.ouom.neriplayer.data.unified.SourceMatchBackend
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate

/** User-requested check. Returns no URL; never starts playback, enqueues media prefetch or confirms a match. */
suspend fun PlayerManager.checkSourceAvailability(song: SongItem, candidate: RecordingSourceCandidate): SourceCheckResult =
    withContext(Dispatchers.IO) {
        withTimeoutOrNull(8_000) { checkSourceAvailabilityWithinBudget(song, candidate) }
            ?: SourceCheckResult(SourceCheckStatus.TIMED_OUT, System.currentTimeMillis())
    }

private suspend fun PlayerManager.checkSourceAvailabilityWithinBudget(song: SongItem, candidate: RecordingSourceCandidate): SourceCheckResult {
    val backend = SourceMatchBackend(application)
    val provider = candidate.key.provider
    val session = sourceResolutionSession(provider)
    val decision = backend.decisionStamp(song, candidate.key)
    return SourceAvailabilityChecker().check(
        expected = song.recordingMetadata(), candidate = candidate,
        enabled = provider != "youtube_music" || YouTubeFeatureGate.isEnabled(),
        online = isPlaybackNetworkUsable(application), rejected = decision.decision == SourceMatchDecision.REJECTED,
        stillCurrent = {
            session == sourceResolutionSession(provider) && decision == backend.decisionStamp(song, candidate.key) &&
                (provider == "local" || isPlaybackNetworkUsable(application))
        }
    ) {
        when (val attempt = resolveSourceCandidate(song, candidate,
            RefreshResolverSideEffects(RefreshSideEffectGate { false }))) {
            is SourceAttempt.Ready -> SourceAttempt.Ready(Unit)
            is SourceAttempt.Rejected -> attempt
        }
    }
}
