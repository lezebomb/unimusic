package moe.ouom.neriplayer.ui.screen.tab.settings.component.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.unified.ConfiguredIdentityWebDavSync
import moe.ouom.neriplayer.data.unified.IdentityWebDavNotConfiguredException
import moe.ouom.neriplayer.data.sync.unified.IdentitySyncConfigurationChangedException
import moe.ouom.neriplayer.data.sync.unified.UnifiedIdentitySyncConflictException
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

private data class IdentitySyncSummary(val live: Int, val deleted: Int, val conflicts: Int, val excluded: Int,
    val localConflicts: Int, val pending: Boolean)

/** Scope is cancelled when the settings page leaves composition; credentials never enter UI state. */
@Composable
internal fun SettingsIdentitySyncSection(configured: Boolean, onConfigure: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<IdentitySyncSummary?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    Column {
        ListItem(
            headlineContent = { Text(stringResource(CoreCommonR.string.unimusic_identity_sync_title)) },
            supportingContent = { Text(stringResource(CoreCommonR.string.unimusic_identity_sync_description)) },
            trailingContent = {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else MiuixSettingsTextButton(onClick = {
                    if (!configured) onConfigure() else {
                        busy = true;error = null;result = null
                        scope.launch {
                            try {
                                val synced = ConfiguredIdentityWebDavSync(context, AppContainer.sharedOkHttpClient).synchronize()
                                result = IdentitySyncSummary(synced.remote.liveRecordings, synced.remote.deletedRecordings,
                                    synced.remote.conflicts, synced.capture.excludedDeviceSources + synced.capture.excludedUnsupportedSources,
                                    synced.capture.journal.conflictingLocalGroups, synced.remote.pendingLocalChanges)
                            }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = when (failure) {
                                    is IdentityWebDavNotConfiguredException -> CoreCommonR.string.unimusic_identity_sync_configure
                                    is IdentitySyncConfigurationChangedException -> CoreCommonR.string.unimusic_identity_sync_changed
                                    is UnifiedIdentitySyncConflictException -> CoreCommonR.string.unimusic_identity_sync_racing
                                    else -> CoreCommonR.string.unimusic_identity_sync_failed
                                }
                            } finally { busy = false }
                        }
                    }
                }) { Text(stringResource(if (configured) CoreCommonR.string.unimusic_identity_sync_action
                    else CoreCommonR.string.unimusic_identity_sync_configure)) }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        error?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        result?.let { value ->
            Text(stringResource(CoreCommonR.string.unimusic_identity_sync_result,
                value.live, value.deleted, value.conflicts), Modifier.padding(horizontal = 16.dp))
            if (value.excluded > 0) {
                Text(stringResource(CoreCommonR.string.unimusic_identity_sync_excluded,
                    value.excluded), Modifier.padding(horizontal = 16.dp))
            }
            if (value.localConflicts > 0) {
                Text(stringResource(CoreCommonR.string.unimusic_identity_sync_local_conflict), Modifier.padding(horizontal = 16.dp))
            }
            if (value.pending) {
                Text(stringResource(CoreCommonR.string.unimusic_identity_sync_pending), Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
