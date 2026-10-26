package moe.ouom.neriplayer.ui.screen.tab.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.unified.PlaylistImportMode
import moe.ouom.neriplayer.data.model.unified.PlaylistImportProgress
import moe.ouom.neriplayer.data.model.unified.PlaylistImportState
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistImportUiState
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistImportViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.canCancelImport
import moe.ouom.neriplayer.ui.viewmodel.tab.canRetryImport
import moe.ouom.neriplayer.ui.viewmodel.tab.importFailureText
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog

@Composable
internal fun PlaylistImportJobs(
    state: PlaylistImportUiState,
    offlineMode: Boolean,
    vm: PlaylistImportViewModel,
    modifier: Modifier = Modifier
) {
    var unlinkJob by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(stringResource(R.string.unimusic_import_jobs_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (offlineMode || !state.signedIn) {
            item {
                Text(stringResource(if (offlineMode) R.string.unimusic_import_offline else R.string.unimusic_import_login_required),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.loadingJobs) {
            item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        }
        if (state.jobsFailed) {
            item {
                Text(stringResource(R.string.unimusic_import_jobs_failed), color = MaterialTheme.colorScheme.error)
                HapticTextButton(onClick = vm::observeJobs) { Text(stringResource(R.string.unimusic_import_retry)) }
            }
        } else if (!state.loadingJobs && state.jobs.isEmpty()) {
            item { Text(stringResource(R.string.unimusic_import_empty_jobs), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.jobs, key = { it.jobId }) { job ->
            PlaylistImportJobCard(
                job = job,
                busy = state.enqueueing || job.jobId in state.busyJobs,
                canRead = state.signedIn && !offlineMode,
                onCancel = { vm.cancel(job.jobId) },
                onRetry = { vm.retry(job.jobId) },
                onRefresh = { vm.refresh(job.jobId) },
                onUnlink = { unlinkJob = job.jobId },
                onFailures = { vm.showFailures(job.jobId) }
            )
        }
    }
    unlinkJob?.let { id ->
        AlertDialog(
            onDismissRequest = { unlinkJob = null },
            title = { Text(stringResource(R.string.unimusic_import_unlink)) },
            text = { Text(stringResource(R.string.unimusic_import_unlink_confirmation)) },
            confirmButton = {
                HapticTextButton(onClick = { unlinkJob = null; vm.unlink(id) },
                    enabled = !state.enqueueing && id !in state.busyJobs) {
                    Text(stringResource(R.string.unimusic_import_unlink))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { unlinkJob = null }) { Text(stringResource(R.string.unimusic_import_keep_link)) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistImportJobCard(
    job: PlaylistImportProgress,
    busy: Boolean,
    canRead: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onUnlink: () -> Unit,
    onFailures: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(job.playlistName?.takeIf(String::isNotBlank)
                ?: stringResource(R.string.unimusic_import_playlist_fallback, job.remotePlaylistId),
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.unimusic_import_job_account, job.accountKey),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(importStateText(job.state)), style = MaterialTheme.typography.labelLarge)
            }
            Text(stringResource(when {
                job.linked -> R.string.unimusic_import_linked_status
                job.mode == PlaylistImportMode.LINKED && job.playlistId == null -> R.string.unimusic_import_link_pending
                job.mode == PlaylistImportMode.LINKED -> R.string.unimusic_import_unlinked_status
                else -> R.string.unimusic_import_once
            }), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (job.total > 0) {
                val processed = (job.imported + job.failed + job.skipped).coerceIn(0, job.total)
                LinearProgressIndicator(progress = { processed.toFloat() / job.total }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.unimusic_import_progress, processed, job.total), style = MaterialTheme.typography.bodySmall)
            } else if (job.state == PlaylistImportState.QUEUED || job.state == PlaylistImportState.RUNNING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.unimusic_import_counts, job.imported, job.failed, job.skipped),
                style = MaterialTheme.typography.bodyMedium)
            job.lastError?.let { error ->
                Text(stringResource(importFailureText(error)), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (job.retryAt > System.currentTimeMillis()) {
                Text(stringResource(R.string.unimusic_import_retry_time, importTimeText(job.retryAt)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canCancelImport(job.state)) {
                    HapticTextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.unimusic_import_cancel)) }
                }
                if (canRetryImport(job)) {
                    HapticTextButton(onClick = onRetry, enabled = !busy && canRead) { Text(stringResource(R.string.unimusic_import_retry)) }
                }
                if (job.failed > 0) {
                    HapticTextButton(onClick = onFailures, enabled = !busy) { Text(stringResource(R.string.unimusic_import_failures)) }
                }
                if (job.linked) {
                    HapticTextButton(onClick = onRefresh, enabled = !busy && canRead && !canCancelImport(job.state)) {
                        Text(stringResource(R.string.unimusic_import_refresh))
                    }
                    HapticTextButton(onClick = onUnlink, enabled = !busy) { Text(stringResource(R.string.unimusic_import_unlink)) }
                }
            }
        }
    }
}

@Composable
internal fun PlaylistImportFailuresDialog(state: PlaylistImportUiState, vm: PlaylistImportViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissFailures,
        title = { Text(stringResource(R.string.unimusic_import_failures)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.unimusic_import_failures_hint), style = MaterialTheme.typography.bodySmall)
                }
                items(state.failures, key = { "${it.position}:${it.resourceId}" }) { failure ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.unimusic_import_failure_item, failure.position + 1, failure.resourceId),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(importFailureText(failure.kind)), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.unimusic_import_attempts, failure.attempts), style = MaterialTheme.typography.bodySmall)
                        if (failure.retryAt > System.currentTimeMillis()) {
                            Text(stringResource(R.string.unimusic_import_retry_time, importTimeText(failure.retryAt)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.loadingFailures) {
                    item { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                } else if (state.failuresFailed) {
                    item {
                        Text(stringResource(R.string.unimusic_import_failures_failed), color = MaterialTheme.colorScheme.error)
                        HapticTextButton(onClick = vm::loadMoreFailures) { Text(stringResource(R.string.unimusic_import_retry)) }
                    }
                } else if (state.moreFailures) {
                    item { HapticTextButton(onClick = vm::loadMoreFailures) { Text(stringResource(R.string.unimusic_import_load_more)) } }
                } else if (state.failures.isEmpty()) {
                    item { Text(stringResource(R.string.unimusic_import_no_failures)) }
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = vm::dismissFailures) { Text(stringResource(R.string.unimusic_import_close)) }
        },
        dismissButton = {
            HapticTextButton(onClick = { state.failureJobId?.let(vm::showFailures) }, enabled = !state.loadingFailures) {
                Text(stringResource(R.string.unimusic_import_refresh_details))
            }
        }
    )
}

private fun importTimeText(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

private fun importStateText(state: PlaylistImportState): Int = when (state) {
    PlaylistImportState.QUEUED -> R.string.unimusic_import_state_queued
    PlaylistImportState.RUNNING -> R.string.unimusic_import_state_running
    PlaylistImportState.RETRY_WAIT -> R.string.unimusic_import_state_retry_wait
    PlaylistImportState.PAUSED_AUTH -> R.string.unimusic_import_state_auth
    PlaylistImportState.COMPLETED -> R.string.unimusic_import_state_completed
    PlaylistImportState.PARTIAL_FAILED -> R.string.unimusic_import_state_partial
    PlaylistImportState.CANCELLED -> R.string.unimusic_import_state_cancelled
    PlaylistImportState.FAILED -> R.string.unimusic_import_state_failed
}
