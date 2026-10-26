package moe.ouom.neriplayer.ui.screen.tab.explore

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.unified.CatalogTrack
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.viewmodel.tab.catalogExternalPageUrl
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.util.platform.tryStartActivity

@Composable
internal fun CatalogSearchResultRow(track: CatalogTrack) {
    val context = LocalContext.current
    val metadata = track.metadata
    val pageUrl = catalogExternalPageUrl(track)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(neriRoundedShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = metadata.title.ifBlank {
                        stringResource(CoreCommonR.string.lyrics_unknown_song)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = exploreSongSubtitle(metadata.artist, metadata.album)
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (metadata.durationMs > 0L) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatDuration(metadata.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${stringResource(CoreCommonR.string.explore_catalog_kuwo)} · " +
                    stringResource(CoreCommonR.string.explore_catalog_metadata_only),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HapticTextButton(
                enabled = pageUrl != null,
                onClick = {
                    if (pageUrl != null && !context.tryStartActivity(
                            Intent(Intent.ACTION_VIEW, pageUrl.toUri())
                                .addCategory(Intent.CATEGORY_BROWSABLE)
                        )
                    ) {
                        AppFeedback.showToast(
                            context = context,
                            message = context.getString(CoreCommonR.string.explore_catalog_open_failed)
                        )
                    }
                }
            ) {
                Text(stringResource(CoreCommonR.string.explore_catalog_view_on_kuwo))
            }
        }
    }
}
