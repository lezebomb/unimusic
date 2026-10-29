package moe.ouom.neriplayer.data.recommendation

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val statisticsAvailable: Boolean = true,
    val feedbackAvailable: Boolean = true,
    val hasDismissedCandidates: Boolean = false,
    val playbackFeedbackEnabled: Boolean = true
)

/** Local rules, daily snapshots and explicit device-only recommendation preferences. */
class LocalRecommendationRepository(
    private val context: Context,
    private val playbackStats: PlaybackStatsRepository,
    private val dailySnapshots: DailyRecommendationStore = DailyRecommendationStore.getInstance(context),
    private val feedback: LocalRecommendationFeedbackStore = LocalRecommendationFeedbackStore.getInstance(context),
    private val playbackFeedback: LocalPlaybackFeedbackStore = LocalPlaybackFeedbackStore.getInstance(context)
) {
    suspend fun recommend(
        playlists: List<LocalPlaylist>,
        nowMillis: Long = System.currentTimeMillis(),
        forceRefresh: Boolean = false
    ): LocalRecommendations {
        val candidates = withContext(Dispatchers.Default) {
            val workContext = currentCoroutineContext()
            playlists.flatMap { playlist ->
                workContext.ensureActive()
                val favorite = FavoritesPlaylist.isSystemPlaylist(playlist, context)
                playlist.songs.map { song ->
                    workContext.ensureActive()
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
        var feedbackAvailable = true
        val dismissed = try { feedback.activeDigests(nowMillis) } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            feedbackAvailable = false
            emptySet()
        }
        val eligible = if (dismissed.isEmpty()) candidates else withContext(Dispatchers.Default) {
            val workContext = currentCoroutineContext()
            candidates.filterNot { workContext.ensureActive(); recommendationIdentityDigest(it.identityKey) in dismissed }
        }
        val statisticsClearedAt = playbackStats.statsClearedAtFlow.value.coerceAtLeast(0L)
        val evidence = try { playbackFeedback.signals(nowMillis, statisticsClearedAt) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            LocalPlaybackSignals(emptyMap(), available = false)
        }
        val context = RecommendationEvidenceContext(statisticsClearedAt, evidence.clearedAt, evidence.enabled, evidence.available)
        return dailySnapshots.getOrCreate(eligible, nowMillis, forceRefresh, context) { rankFresh(eligible, nowMillis, evidence.byDigest) }
            .copy(feedbackAvailable = feedbackAvailable && evidence.available,
                hasDismissedCandidates = eligible.size < candidates.size,
                playbackFeedbackEnabled = evidence.enabled)
    }

    private suspend fun rankFresh(candidates: List<LocalRecommendationCandidate>, nowMillis: Long, signals: Map<String, LocalPlaybackSignal>): LocalRecommendations {
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
            val workContext = currentCoroutineContext()
            val feedbackByKey: Map<String, LocalPlaybackSignal> = if (signals.isEmpty()) emptyMap() else candidates.associate { candidate ->
                workContext.ensureActive()
                candidate.identityKey to (signals[recommendationIdentityDigest(candidate.identityKey)] ?: LocalPlaybackSignal())
            }
            val favoriteKeys = candidates.filter { workContext.ensureActive(); it.favorite }.mapTo(hashSetOf()) { it.identityKey }
            LocalRecommendations(
                items = LocalRecommendationRanker.rank(candidates, statistics, nowMillis, feedback = feedbackByKey, checkCancellation = { workContext.ensureActive() }),
                localFileItems = LocalRecommendationRanker.rank(
                    candidates.filter { workContext.ensureActive(); it.source == LocalRecommendationSource.LOCAL_FILE }
                        .map { it.copy(favorite = it.identityKey in favoriteKeys) }, statistics, nowMillis, feedback = feedbackByKey, checkCancellation = { workContext.ensureActive() }
                ),
                statisticsAvailable = statisticsAvailable
            )
        }
    }
}
