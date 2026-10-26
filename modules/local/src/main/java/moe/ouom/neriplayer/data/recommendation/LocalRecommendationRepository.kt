package moe.ouom.neriplayer.data.recommendation

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsSort
import java.util.Locale

data class LocalRecommendations(
    val items: List<LocalRecommendation> = emptyList(),
    val localFileItems: List<LocalRecommendation> = emptyList(),
    val statisticsAvailable: Boolean = true
)

/** No network calls, writes, new feedback events, or changes to playback identities. */
class LocalRecommendationRepository(
    private val context: Context,
    private val playbackStats: PlaybackStatsRepository
) {
    suspend fun recommend(playlists: List<LocalPlaylist>, nowMillis: Long = System.currentTimeMillis()): LocalRecommendations {
        val candidates = withContext(Dispatchers.Default) {
            playlists.flatMap { playlist ->
                val favorite = FavoritesPlaylist.isSystemPlaylist(playlist, context)
                playlist.songs.map { song ->
                    val source = when {
                        LocalSongSupport.isLocalSong(song, context) -> LocalRecommendationSource.LOCAL_FILE
                        else -> when (song.channelId?.lowercase(Locale.ROOT) ?: song.album.lowercase(Locale.ROOT)) {
                            "netease", "网易云音乐" -> LocalRecommendationSource.NETEASE
                            "bilibili", "bili", "哔哩哔哩" -> LocalRecommendationSource.BILIBILI
                            "youtube", "youtube_music", "youtubemusic" -> LocalRecommendationSource.YOUTUBE_MUSIC
                            else -> LocalRecommendationSource.LIBRARY
                        }
                    }
                    LocalRecommendationCandidate(song, song.stableKey(), source, favorite)
                }
            }
        }
        if (candidates.isEmpty()) return LocalRecommendations()
        var statisticsAvailable = true
        val statistics = try {
            // Bounded queries use the existing indexed paging API. Missing rows are unknown history.
            val recent = playbackStats.readPage(PlaybackStatsQuery(sort = PlaybackStatsSort.RECENT, nowMillis = nowMillis), pageSize = 256)
            val familiar = playbackStats.readPage(PlaybackStatsQuery(sort = PlaybackStatsSort.LISTEN_TIME, nowMillis = nowMillis), pageSize = 256)
            (recent.tracks + familiar.tracks).distinctBy(TrackStat::identityKey)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            statisticsAvailable = false
            emptyList()
        }
        return withContext(Dispatchers.Default) {
            val favoriteKeys = candidates.filter { it.favorite }.mapTo(hashSetOf()) { it.identityKey }
            LocalRecommendations(
                items = LocalRecommendationRanker.rank(candidates, statistics, nowMillis),
                localFileItems = LocalRecommendationRanker.rank(
                    candidates.filter { it.source == LocalRecommendationSource.LOCAL_FILE }
                        .map { it.copy(favorite = it.identityKey in favoriteKeys) }, statistics, nowMillis
                ),
                statisticsAvailable = statisticsAvailable
            )
        }
    }
}
