package moe.ouom.neriplayer.core.player.playback

import android.content.Context
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.stats.AppPlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackFeedbackTracker
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsOwner
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsTracker
import moe.ouom.neriplayer.core.player.session.AppQueueSongIdentity
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate

internal fun PlayerManager.initializePlaybackStatistics(app: Context) {
    AppPlaybackStatsPendingWrites.bind(app)
    AppPlaybackStatsPendingWrites.queue.activate(AppPlaybackStatsWritePort)
    playbackStatsOwner = PlaybackStatsOwner(
        ioScope, AppPlaybackStatsWritePort,
        PlaybackStatsTracker(AppQueueSongIdentity::stableKey, readClearedAt = AppPlaybackStatsWritePort::clearedAt),
        AppPlaybackStatsPendingWrites.queue,
        feedback = PlaybackFeedbackTracker(AppQueueSongIdentity::stableKey, readClearedAt = {
            maxOf(AppPlaybackStatsWritePort.clearedAt(),
                moe.ouom.neriplayer.data.recommendation.LocalPlaybackFeedbackStore.captureResetEpoch())
        }),
        feedbackEligible = {
            currentPlaybackCandidate()?.isPreviewClip != true && _currentPlaybackAudioInfo.value?.isPreviewClip != true
        }
    )
}
