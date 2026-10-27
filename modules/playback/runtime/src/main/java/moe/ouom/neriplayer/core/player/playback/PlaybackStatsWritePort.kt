package moe.ouom.neriplayer.core.player.playback

import android.content.Context
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsSnapshot
import moe.ouom.neriplayer.core.player.runtime.stats.PlaybackStatsWritePort
import moe.ouom.neriplayer.core.player.runtime.stats.countedLocalPlaylistId
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository

internal object AppPlaybackStatsWritePort : PlaybackStatsWritePort {
    @Volatile private var application: Context? = null
    @Volatile private var feedbackStore: moe.ouom.neriplayer.data.recommendation.LocalPlaybackFeedbackStore? = null

    fun bind(context: Context) {
        val resolved = context.applicationContext ?: context
        if (application !== resolved) feedbackStore = null
        application = resolved
    }

    private fun playbackRepository(): PlaybackStatsRepository = if (PlayerDependencies.isInitialized()) {
        PlayerDependencies.repositories.playbackStatsRepo
    } else application?.let(PlaybackStatsRepository::getInstance) ?: PlayerDependencies.repositories.playbackStatsRepo

    private fun playlistRepository(): LocalPlaylistPlaybackStatsRepository = if (PlayerDependencies.isInitialized()) {
        PlayerDependencies.repositories.localPlaylistPlaybackStatsRepo
    } else application?.let(LocalPlaylistPlaybackStatsRepository::getInstance) ?: PlayerDependencies.repositories.localPlaylistPlaybackStatsRepo

    override suspend fun record(snapshot: PlaybackStatsSnapshot) {
        playbackRepository().recordListenDeltaNow(
            song = snapshot.song,
            listenedMs = snapshot.listenedMs,
            playCountIncrement = snapshot.playCountIncrement,
            scheduleSync = snapshot.scheduleSync,
            eventId = snapshot.eventId,
            playedAt = snapshot.playedAt,
            observedClearedAt = snapshot.observedClearedAt
        )
        countedLocalPlaylistId(snapshot)?.let { playlistId ->
            playlistRepository().recordPlayNow(playlistId, snapshot.playedAt, snapshot.eventId)
        }
    }

    override fun recordFeedback(song: moe.ouom.neriplayer.data.model.SongItem,
        events: List<moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackEvent>) {
        application?.let { context ->
            val store = feedbackStore ?: moe.ouom.neriplayer.data.recommendation.LocalPlaybackFeedbackStore.getInstance(context)
                .also { feedbackStore = it }
            store.enqueue(song.stableKey(), events, clearedAt())
        }
    }

    override fun hasPendingWrites(): Boolean = playbackRepository().hasPendingWrites() || feedbackStore?.hasPendingWrites() == true

    fun clearedAt(): Long = playbackRepository().statsClearedAtFlow.value

    override suspend fun flushPendingWrites() {
        playbackRepository().flushPendingWrites()
        feedbackStore?.flush()
    }
}
