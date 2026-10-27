package moe.ouom.neriplayer.core.player.runtime.stats

interface PlaybackStatsWritePort {
    suspend fun record(snapshot: PlaybackStatsSnapshot)

    fun recordFeedback(song: moe.ouom.neriplayer.data.model.SongItem,
        events: List<moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackEvent>) { }

    fun hasPendingWrites(): Boolean

    suspend fun flushPendingWrites()
}

fun countedLocalPlaylistId(snapshot: PlaybackStatsSnapshot): Long? =
    if (snapshot.playCountIncrement > 0) snapshot.localPlaylistId else null
