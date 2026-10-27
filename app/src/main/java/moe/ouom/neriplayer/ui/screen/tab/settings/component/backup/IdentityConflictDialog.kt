package moe.ouom.neriplayer.ui.screen.tab.settings.component.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.sync.unified.IdentityConflictPage
import moe.ouom.neriplayer.data.sync.unified.StaleIdentityDecisionException

@Composable
internal fun IdentityConflictDialog(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<IdentityConflictPage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    fun load(offset: Int) {
        busy = true
        scope.launch {
            try { page = AppContainer.unifiedIdentitySyncBackend.conflictsPage(offset) }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                message = CoreCommonR.string.unimusic_identity_sync_failed
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { load(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.unimusic_identity_conflicts_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(CoreCommonR.string.unimusic_identity_conflicts_description))
            message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
            val current = page
            if (busy) Text(stringResource(CoreCommonR.string.unimusic_identity_conflicts_loading))
            if (current != null) {
                Text(stringResource(CoreCommonR.string.unimusic_identity_conflicts_count, current.total))
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(current.items, key = { it.songId }) { item ->
                        Column {
                            item.choices.forEach { choice ->
                                TextButton(enabled = !busy, onClick = {
                                    busy = true;message = null
                                    scope.launch {
                                        try {
                                            AppContainer.unifiedIdentitySyncBackend.resolveConflict(item, choice)
                                            onChanged();message = CoreCommonR.string.unimusic_identity_conflicts_saved
                                        } catch (failure: Exception) {
                                            if (failure is CancellationException) throw failure
                                            message = if (failure is StaleIdentityDecisionException)
                                                CoreCommonR.string.unimusic_identity_conflicts_stale else CoreCommonR.string.unimusic_identity_sync_failed
                                        } finally {
                                            try { page = AppContainer.unifiedIdentitySyncBackend.conflictsPage() }
                                            catch (failure: Exception) { if (failure is CancellationException) throw failure }
                                            busy = false
                                        }
                                    }
                                }) {
                                    val metadata = choice.metadata
                                    if (metadata == null) Text(stringResource(CoreCommonR.string.unimusic_identity_conflicts_delete))
                                    else Column {
                                        Text(stringResource(if (choice.local) CoreCommonR.string.unimusic_identity_conflicts_local
                                            else CoreCommonR.string.unimusic_identity_conflicts_synced))
                                        Text(metadata.title)
                                        Text(stringResource(CoreCommonR.string.unimusic_identity_conflicts_metadata,
                                            metadata.artist, metadata.album, metadata.durationMs / 1000,
                                            stringResource(identityVersionLabel(metadata.recordingVersion))))
                                    }
                                }
                            }
                        }
                    }
                }
                Row {
                    TextButton(enabled = !busy && current.offset > 0, onClick = { load((current.offset - 20).coerceAtLeast(0)) }) {
                        Text(stringResource(CoreCommonR.string.unimusic_identity_conflicts_previous))
                    }
                    TextButton(enabled = !busy && current.offset + current.items.size < current.total,
                        onClick = { load(current.offset + 20) }) { Text(stringResource(CoreCommonR.string.unimusic_identity_conflicts_next)) }
                }
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreCommonR.string.unimusic_feedback_done)) } }
    )
}

private fun identityVersionLabel(version: String): Int = when (version) {
    "STUDIO" -> CoreCommonR.string.unimusic_identity_version_studio
    "LIVE" -> CoreCommonR.string.unimusic_identity_version_live
    "COVER" -> CoreCommonR.string.unimusic_identity_version_cover
    "INSTRUMENTAL" -> CoreCommonR.string.unimusic_identity_version_instrumental
    "REMIX" -> CoreCommonR.string.unimusic_identity_version_remix
    "EDIT" -> CoreCommonR.string.unimusic_identity_version_edit
    "SPEED_ALTERED" -> CoreCommonR.string.unimusic_identity_version_speed
    "REMASTER" -> CoreCommonR.string.unimusic_identity_version_remaster
    "RERECORDING" -> CoreCommonR.string.unimusic_identity_version_rerecording
    else -> CoreCommonR.string.unimusic_identity_version_unknown
}
