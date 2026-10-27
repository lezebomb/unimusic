package moe.ouom.neriplayer.ui.viewmodel.tab.home

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.recommendation.LocalRecommendationRepository
import moe.ouom.neriplayer.data.recommendation.LocalRecommendation
import moe.ouom.neriplayer.data.recommendation.LocalRecommendationFeedbackStore
import moe.ouom.neriplayer.data.recommendation.RecommendationDismissal
import moe.ouom.neriplayer.data.recommendation.LocalRecommendations
import moe.ouom.neriplayer.data.recommendation.BeijingRecommendationDay
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import java.io.IOException

data class HomeLocalRecommendationsState(
    val recommendations: LocalRecommendations = LocalRecommendations(),
    val loading: Boolean = true,
    val failed: Boolean = false
)

/** Kept separate from remote Home state so account changes cannot replace the local section. */
internal class HomeLocalRecommendationsController(application: Application, scope: CoroutineScope) {
    private val feedback = LocalRecommendationFeedbackStore.getInstance(application)
    private val playbackFeedback = moe.ouom.neriplayer.data.recommendation.LocalPlaybackFeedbackStore.getInstance(application)
    private var handledFeedbackRevision = feedback.revision.value to playbackFeedback.revision.value
    private val refreshVersion = MutableStateFlow(0L)
    private var handledRefreshVersion = 0L
    val state = channelFlow {
        var latest = LocalRecommendations()
        refreshVersion.collectLatest { requestedRefreshVersion ->
            send(HomeLocalRecommendationsState(latest, loading = true))
            try {
                val library = LocalPlaylistRepository.getInstance(application)
                if (!library.awaitInitialized()) throw IOException("Local library unavailable")
                val statistics = PlaybackStatsRepository.getInstance(application)
                val recommendations = LocalRecommendationRepository(application, statistics)
                combine(
                    library.playlists,
                    statistics.revisionFlow.catch { failure ->
                        if (failure is CancellationException) throw failure
                        emit(-1L)
                    },
                    recommendationDays(),
                    combine(feedback.revision, playbackFeedback.revision) { explicit, playback -> explicit to playback }
                ) { playlists, _, _, feedbackRevision ->
                    playlists.map { it.copy(songs = it.songs.toMutableList()) } to feedbackRevision
                }
                    .conflate()
                    .collectLatest { (playlists, feedbackRevision) ->
                        send(HomeLocalRecommendationsState(latest, loading = true))
                        try {
                            latest = recommendations.recommend(
                                playlists,
                                forceRefresh = requestedRefreshVersion > handledRefreshVersion || feedbackRevision != handledFeedbackRevision
                            )
                            handledRefreshVersion = requestedRefreshVersion
                            handledFeedbackRevision = feedbackRevision
                            send(HomeLocalRecommendationsState(latest, loading = false))
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            latest = LocalRecommendations()
                            send(HomeLocalRecommendationsState(loading = false, failed = true))
                        }
                    }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                latest = LocalRecommendations()
                send(HomeLocalRecommendationsState(loading = false, failed = true))
            }
        }
    }.flowOn(Dispatchers.IO).stateIn(
        scope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        HomeLocalRecommendationsState()
    )

    suspend fun dismiss(item: LocalRecommendation): RecommendationDismissal = feedback.dismiss(item.identityKey)
    suspend fun undo(token: RecommendationDismissal) = feedback.undo(token)
    suspend fun resetFeedback() = feedback.reset()

    suspend fun setPlaybackFeedbackEnabled(enabled: Boolean) = playbackFeedback.setEnabled(enabled)
    suspend fun clearPlaybackFeedback() = playbackFeedback.clearSignals()

    fun refresh() {
        refreshVersion.value += 1L
    }

    private fun recommendationDays() = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(BeijingRecommendationDay.key(now))
            delay((BeijingRecommendationDay.nextStartMillis(now) - now).coerceAtLeast(1L))
        }
    }
}
