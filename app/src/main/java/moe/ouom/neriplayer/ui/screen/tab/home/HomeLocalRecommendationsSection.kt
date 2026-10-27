package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.recommendation.LocalRecommendation
import moe.ouom.neriplayer.data.recommendation.LocalRecommendationReason
import moe.ouom.neriplayer.data.recommendation.LocalRecommendationSource
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl
import moe.ouom.neriplayer.ui.viewmodel.tab.home.HomeLocalRecommendationsState

@Composable
internal fun HomeLocalRecommendationsSection(
    state: HomeLocalRecommendationsState,
    offlineMode: Boolean,
    onRefresh: () -> Unit,
    onDismiss: (LocalRecommendation) -> Unit = {},
    onResetFeedback: () -> Unit = {},
    onSetPlaybackFeedbackEnabled: (Boolean) -> Unit = {},
    onClearPlaybackFeedback: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit
) {
    var showPreferences by remember { mutableStateOf(false) }
    if (showPreferences) LocalRecommendationPreferencesDialog(state, { showPreferences = false },
        onSetPlaybackFeedbackEnabled, onClearPlaybackFeedback)
    val items = if (offlineMode) state.recommendations.localFileItems else state.recommendations.items
    val songs = items.map { it.song }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(CoreCommonR.string.unimusic_recommendations_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (state.recommendations.hasDismissedCandidates) {
                TextButton(onClick = onResetFeedback, enabled = !state.loading) {
                    Text(stringResource(CoreCommonR.string.unimusic_recommendations_restore))
                }
            }
            IconButton(onClick = { showPreferences = true }) {
                Icon(Icons.Default.Tune, stringResource(CoreCommonR.string.unimusic_feedback_preferences))
            }
            if (state.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(Icons.Default.Refresh, stringResource(CoreCommonR.string.unimusic_recommendations_refresh))
            }
        }
        Text(
            stringResource(CoreCommonR.string.unimusic_recommendations_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when {
            state.failed -> LocalRecommendationNotice(CoreCommonR.string.unimusic_recommendations_error)
            items.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(items, key = { _, item -> item.identityKey }) { index, item ->
                    LocalRecommendationCard(item, onClick = { onSongClick(songs, index) }, onDismiss = { onDismiss(item) })
                }
            }
            !state.loading -> LocalRecommendationNotice(
                if (state.recommendations.hasDismissedCandidates) {
                    CoreCommonR.string.unimusic_recommendations_all_hidden
                } else if (offlineMode && state.recommendations.items.isNotEmpty()) {
                    CoreCommonR.string.unimusic_recommendations_offline_empty
                } else CoreCommonR.string.unimusic_recommendations_empty
            )
        }
        if (!state.failed && !state.recommendations.feedbackAvailable) {
            LocalRecommendationNotice(CoreCommonR.string.unimusic_recommendations_feedback_error)
        }
        if (!state.failed && !state.recommendations.statisticsAvailable) {
            LocalRecommendationNotice(CoreCommonR.string.unimusic_recommendations_statistics_unavailable)
        }
    }
}

@Composable
private fun LocalRecommendationNotice(textResource: Int) {
    Text(
        stringResource(textResource),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LocalRecommendationCard(item: LocalRecommendation, onClick: () -> Unit, onDismiss: () -> Unit) {
    val song = item.song
    Surface(shape = NeriDesign.tokens.cardShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier.width(240.dp).clickable(onClick = onClick).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = rememberSongDisplayCoverUrl(song),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(NeriDesign.tokens.cardShape)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(song.displayName(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.displayArtist(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                stringResource(when (item.reason) {
                    LocalRecommendationReason.EXPLORE_LIBRARY -> CoreCommonR.string.unimusic_recommendations_reason_explore
                    LocalRecommendationReason.RECENTLY_ADDED -> CoreCommonR.string.unimusic_recommendations_reason_added
                    LocalRecommendationReason.FAMILIAR_ARTIST -> CoreCommonR.string.unimusic_recommendations_reason_artist
                    LocalRecommendationReason.REDISCOVER -> CoreCommonR.string.unimusic_recommendations_reason_rediscover
                    LocalRecommendationReason.LESS_PLAYED -> CoreCommonR.string.unimusic_recommendations_reason_less_played
                }),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(when (item.source) {
                        LocalRecommendationSource.LOCAL_FILE -> CoreCommonR.string.unimusic_recommendations_source_file
                        LocalRecommendationSource.NETEASE -> CoreCommonR.string.unimusic_recommendations_source_netease
                        LocalRecommendationSource.BILIBILI -> CoreCommonR.string.unimusic_recommendations_source_bilibili
                        LocalRecommendationSource.YOUTUBE_MUSIC -> CoreCommonR.string.unimusic_recommendations_source_youtube
                        LocalRecommendationSource.LIBRARY -> CoreCommonR.string.unimusic_recommendations_source_library
                    }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.VisibilityOff, stringResource(CoreCommonR.string.unimusic_recommendations_dismiss))
                }
            }
        }
    }
}
