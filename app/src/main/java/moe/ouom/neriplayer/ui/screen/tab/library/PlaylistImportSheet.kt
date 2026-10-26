package moe.ouom.neriplayer.ui.screen.tab.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.unified.PlaylistImportMode
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistImportUiState
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistImportViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.normalizeImportAccount
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistImportSheet(offlineMode: Boolean, onDismiss: () -> Unit) {
    val vm: PlaylistImportViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // Only a new batch switches tabs; reopening a finished task list does not submit anything.
    var observedBatch by rememberSaveable { mutableIntStateOf(state.queuedBatch) }
    LaunchedEffect(state.queuedBatch) {
        if (state.queuedBatch > observedBatch) selectedTab = 1
        observedBatch = state.queuedBatch
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.unimusic_import_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                HapticIconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.unimusic_import_close))
                }
            }
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    label = { Text(stringResource(R.string.unimusic_import_choose_tab)) })
                FilterChip(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    label = { Text(stringResource(R.string.unimusic_import_jobs_tab, state.jobs.size)) })
            }
            state.message?.let { message ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(message.resource, *message.counts.toTypedArray()),
                            color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        message.detailResource?.let { resource ->
                            Text(stringResource(resource), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HapticIconButton(onClick = vm::dismissMessage) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.unimusic_import_dismiss_message),
                            modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (selectedTab == 0) {
                ImportPlaylistSelection(state, offlineMode, vm, Modifier.weight(1f))
            } else {
                PlaylistImportJobs(state, offlineMode, vm, Modifier.weight(1f))
            }
        }
    }
    if (state.failureJobId != null) PlaylistImportFailuresDialog(state, vm)
}

@Composable
private fun ImportPlaylistSelection(
    state: PlaylistImportUiState,
    offlineMode: Boolean,
    vm: PlaylistImportViewModel,
    modifier: Modifier
) {
    val canRead = state.signedIn && !offlineMode && !state.enqueueing && !state.loadingAccount && !state.loadingCatalog
    Column(modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(stringResource(R.string.unimusic_import_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (offlineMode || !state.signedIn) {
                item {
                    Text(stringResource(if (offlineMode) R.string.unimusic_import_offline else R.string.unimusic_import_login_required),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                OutlinedTextField(
                    value = state.accountInput,
                    onValueChange = vm::setAccountInput,
                    label = { Text(stringResource(R.string.unimusic_import_account_label)) },
                    supportingText = { Text(stringResource(R.string.unimusic_import_account_hint)) },
                    singleLine = true,
                    enabled = !state.enqueueing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    HapticTextButton(onClick = vm::useSignedInAccount, enabled = canRead) {
                        Text(stringResource(R.string.unimusic_import_use_account))
                    }
                    Button(onClick = vm::loadCatalog, enabled = canRead && normalizeImportAccount(state.accountInput) != null) {
                        Text(stringResource(R.string.unimusic_import_load))
                    }
                }
            }
            val previousAccounts = state.jobs.map { it.accountKey }.distinct()
            if (previousAccounts.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.unimusic_import_previous_accounts), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        previousAccounts.forEach { account ->
                            FilterChip(selected = normalizeImportAccount(state.accountInput) == account,
                                enabled = !state.enqueueing && !state.loadingAccount,
                                onClick = { vm.setAccountInput(account) }, label = { Text(account) })
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.unimusic_import_mode_title), style = MaterialTheme.typography.titleSmall)
                ImportModeOption(state.mode, PlaylistImportMode.ONE_SHOT, !state.enqueueing, vm::setMode,
                    R.string.unimusic_import_once, R.string.unimusic_import_once_hint)
                ImportModeOption(state.mode, PlaylistImportMode.LINKED, !state.enqueueing, vm::setMode,
                    R.string.unimusic_import_linked, R.string.unimusic_import_linked_hint)
            }
            if (state.loadingCatalog || state.loadingAccount) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.unimusic_import_loading))
                    }
                }
            }
            if (state.catalogLoaded) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.unimusic_import_selection_count, state.selected.size, state.catalog.size),
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (state.catalog.isNotEmpty()) {
                            HapticTextButton(onClick = vm::toggleAll, enabled = !state.enqueueing) {
                                Text(stringResource(if (state.selected.size == state.catalog.size)
                                    R.string.unimusic_import_clear_selection else R.string.unimusic_import_select_all))
                            }
                        }
                    }
                }
                if (state.catalog.isEmpty()) {
                    item { Text(stringResource(R.string.unimusic_import_empty_catalog), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            items(state.catalog, key = { it.remoteId }) { playlist ->
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = playlist.remoteId in state.selected, enabled = !state.enqueueing,
                        role = Role.Checkbox, onValueChange = { vm.togglePlaylist(playlist.remoteId) }
                    ).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = playlist.remoteId in state.selected, onCheckedChange = null, enabled = !state.enqueueing)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.unimusic_import_track_count, playlist.trackCount),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Button(
            onClick = vm::enqueueSelected,
            enabled = state.selected.isNotEmpty() && state.catalogAccount != null && state.signedIn && !offlineMode &&
                !state.enqueueing && state.busyJobs.isEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            if (state.enqueueing) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
            }
            Text(stringResource(R.string.unimusic_import_start, state.selected.size))
        }
    }
}

@Composable
private fun ImportModeOption(
    selected: PlaylistImportMode,
    mode: PlaylistImportMode,
    enabled: Boolean,
    onSelect: (PlaylistImportMode) -> Unit,
    title: Int,
    description: Int
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected == mode, enabled = enabled, role = Role.RadioButton,
            onClick = { onSelect(mode) }).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected == mode, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
