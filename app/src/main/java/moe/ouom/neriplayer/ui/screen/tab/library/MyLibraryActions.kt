package moe.ouom.neriplayer.ui.screen.tab.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.R as CoreCommonR

@Composable
internal fun MyLibraryActions(
    onImport: () -> Unit,
    onRecent: () -> Unit,
    onDownloads: () -> Unit,
    onStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryAction(Icons.AutoMirrored.Outlined.PlaylistAdd, stringResource(R.string.unimusic_import_entry), onImport, Modifier.weight(1f))
        LibraryAction(Icons.Outlined.History, stringResource(CoreCommonR.string.library_recent_played), onRecent, Modifier.weight(1f))
        LibraryAction(Icons.Outlined.Download, stringResource(CoreCommonR.string.download_manager), onDownloads, Modifier.weight(1f))
        LibraryAction(Icons.Outlined.BarChart, stringResource(CoreCommonR.string.stats_title), onStats, Modifier.weight(1f))
    }
}

@Composable
private fun LibraryAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}
