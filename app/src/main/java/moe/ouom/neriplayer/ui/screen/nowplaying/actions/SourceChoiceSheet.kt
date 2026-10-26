package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.RecordingMismatch
import moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate
import moe.ouom.neriplayer.data.model.unified.RecordingSourceMatch
import moe.ouom.neriplayer.data.model.unified.RecordingVersion
import moe.ouom.neriplayer.data.model.unified.SourceMatchDecision
import moe.ouom.neriplayer.data.unified.sourceCandidateForSong
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

@Composable
internal fun SourceChoiceAction(song: SongItem, enabled: Boolean = true) {
    var open by remember(song) { mutableStateOf(false) }
    MoreOptionsItem(
        title = stringResource(CoreCommonR.string.unimusic_sources_title),
        icon = Icons.Outlined.LibraryMusic,
        hue = Color(0xFF5578AF),
        enabled = enabled,
        onClick = { open = true }
    )
    if (open) SourceChoiceSheet(song, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceChoiceSheet(song: SongItem, onDismiss: () -> Unit) {
    val owner: SourceChoiceViewModel = viewModel(key = "unimusic-source-choice")
    val state by owner.state.collectAsStateWithLifecycle()
    LaunchedEffect(owner, song) { owner.bind(song) }
    DisposableEffect(owner) { onDispose(owner::close) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        SourceChoiceContent(song, state, owner)
    }
}

@Composable
private fun SourceChoiceContent(song: SongItem, state: SourceChoiceState, owner: SourceChoiceViewModel) {
    val records = if (state.song == song) state.matches.associateBy { it.candidate.key } else emptyMap()
    val candidates = if (state.song == song) {
        (state.matches.map { it.candidate } + state.candidates).associateBy { it.key }.values.toList()
    } else emptyList()
    val originalSource = remember(song) { sourceCandidateForSong(song) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(CoreCommonR.string.unimusic_sources_title), style = MaterialTheme.typography.titleLarge)
                Text(song.originalName ?: song.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(CoreCommonR.string.unimusic_sources_artist_duration,
                    song.originalArtist ?: song.artist, durationText(song.durationMs)))
                originalSource?.let { Text(sourceLabel(it), style = MaterialTheme.typography.labelMedium) }
                Text(stringResource(CoreCommonR.string.unimusic_sources_description),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(CoreCommonR.string.unimusic_sources_discovery_scope),
                    style = MaterialTheme.typography.bodySmall)
                if (!state.youtubeEnabled) Text(stringResource(CoreCommonR.string.unimusic_sources_youtube_disabled),
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HapticTextButton(onClick = owner::refresh, enabled = !state.loading && !state.saving) {
                        Text(stringResource(CoreCommonR.string.unimusic_sources_refresh))
                    }
                    Text(stringResource(CoreCommonR.string.unimusic_sources_count, candidates.size),
                        modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium)
                }
                if (state.loading || state.saving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (state.saving) CoreCommonR.string.unimusic_sources_saving
                        else CoreCommonR.string.unimusic_sources_loading), style = MaterialTheme.typography.bodySmall)
                }
                state.notice?.let { notice ->
                    Text(stringResource(notice.messageRes()), style = MaterialTheme.typography.bodySmall,
                        color = if (notice == SourceChoiceNotice.SAVE_FAILED || notice == SourceChoiceNotice.LOAD_FAILED)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.failures.forEach { (provider, failure) ->
                    Text(stringResource(CoreCommonR.string.unimusic_sources_failure,
                        sourceProviderName(provider), stringResource(failure.messageRes())),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!state.loading && candidates.isEmpty()) {
                    Text(stringResource(CoreCommonR.string.unimusic_sources_empty))
                }
            }
        }
        items(candidates, key = { it.key.toString() }) { candidate ->
            SourceChoiceCard(song, candidate, records[candidate.key],
                enabled = !state.loading && !state.saving && state.song == song,
                confirmationEnabled = candidate.key.provider != "youtube_music" || state.youtubeEnabled,
                onConfirm = { owner.decide(candidate, SourceMatchDecision.CONFIRMED) },
                onReject = { owner.decide(candidate, SourceMatchDecision.REJECTED) },
                onClear = { owner.clear(candidate) })
        }
        item { Text("", Modifier.padding(bottom = 20.dp)) }
    }
}

@Composable
private fun SourceChoiceCard(
    song: SongItem,
    candidate: RecordingSourceCandidate,
    record: RecordingSourceMatch?,
    enabled: Boolean,
    confirmationEnabled: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onClear: () -> Unit
) {
    val assessment = remember(song, candidate) { assessSourceChoice(song, candidate) }
    val supported = candidate.key.provider in setOf("local", "bilibili", "netease", "youtube_music")
    val metadata = candidate.metadata
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sourceLabel(candidate), style = MaterialTheme.typography.labelLarge)
            Text(metadata.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(CoreCommonR.string.unimusic_sources_artist_duration,
                metadata.artist.ifBlank { stringResource(CoreCommonR.string.unimusic_sources_unknown_artist) },
                durationText(metadata.durationMs)), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(CoreCommonR.string.unimusic_sources_version, stringResource(metadata.version.labelRes())),
                style = MaterialTheme.typography.bodySmall)
            if (metadata.album.isNotBlank()) Text(metadata.album, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(when (record?.decision) {
                SourceMatchDecision.CONFIRMED -> if (record?.current == true) CoreCommonR.string.unimusic_sources_confirmed
                    else CoreCommonR.string.unimusic_sources_stale
                SourceMatchDecision.REJECTED -> CoreCommonR.string.unimusic_sources_rejected
                else -> CoreCommonR.string.unimusic_sources_unselected
            }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (!supported) {
                Text(stringResource(CoreCommonR.string.unimusic_sources_metadata_only),
                    style = MaterialTheme.typography.bodySmall)
            } else if (!assessment.accepted) {
                Text(stringResource(assessment.reason.mismatchRes()), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HapticTextButton(onClick = onConfirm,
                    enabled = enabled && confirmationEnabled && supported && assessment.accepted &&
                        (record?.decision != SourceMatchDecision.CONFIRMED || record?.current != true)) {
                    Text(stringResource(CoreCommonR.string.unimusic_sources_confirm))
                }
                HapticTextButton(onClick = onReject,
                    enabled = enabled && record?.decision != SourceMatchDecision.REJECTED) {
                    Text(stringResource(CoreCommonR.string.unimusic_sources_reject))
                }
            }
            if (record != null && record.decision != SourceMatchDecision.NONE) {
                HapticTextButton(onClick = onClear, enabled = enabled) {
                    Text(stringResource(CoreCommonR.string.unimusic_sources_clear))
                }
            }
        }
    }
}

@Composable
private fun sourceLabel(candidate: RecordingSourceCandidate): String = when (candidate.key.provider) {
    "bilibili" -> stringResource(CoreCommonR.string.unimusic_sources_bilibili_key,
        candidate.key.resourceId, candidate.key.subResourceId)
    "local" -> stringResource(CoreCommonR.string.unimusic_sources_local_key, candidate.key.resourceId.takeLast(8))
    "netease" -> stringResource(CoreCommonR.string.unimusic_sources_netease_key, candidate.key.resourceId)
    "youtube_music" -> stringResource(CoreCommonR.string.unimusic_sources_youtube_key, candidate.key.resourceId)
    else -> candidate.key.provider
}

private fun durationText(durationMs: Long): String =
    if (durationMs <= 0) "—" else "${durationMs / 60_000}:${(durationMs / 1000 % 60).toString().padStart(2, '0')}"

@Composable
private fun sourceProviderName(provider: String): String = when (provider) {
    "local" -> stringResource(CoreCommonR.string.unimusic_sources_provider_local)
    "bilibili" -> stringResource(CoreCommonR.string.unimusic_sources_provider_bilibili)
    "netease" -> stringResource(CoreCommonR.string.unimusic_sources_provider_netease)
    "youtube_music" -> stringResource(CoreCommonR.string.unimusic_sources_provider_youtube)
    else -> provider
}

private fun SourceChoiceFailure.messageRes(): Int = when (this) {
    SourceChoiceFailure.TIMED_OUT -> CoreCommonR.string.unimusic_sources_failure_timeout
    SourceChoiceFailure.AUTH_REQUIRED -> CoreCommonR.string.unimusic_sources_failure_login
    SourceChoiceFailure.UNAVAILABLE -> CoreCommonR.string.unimusic_sources_failure_unavailable
}

private fun RecordingMismatch?.mismatchRes(): Int = when (this) {
    RecordingMismatch.VERSION -> CoreCommonR.string.unimusic_sources_mismatch_version
    RecordingMismatch.TITLE -> CoreCommonR.string.unimusic_sources_mismatch_title
    RecordingMismatch.ARTIST -> CoreCommonR.string.unimusic_sources_mismatch_artist
    RecordingMismatch.DURATION -> CoreCommonR.string.unimusic_sources_mismatch_duration
    else -> CoreCommonR.string.unimusic_sources_mismatch_missing
}

private fun RecordingVersion.labelRes(): Int = when (this) {
    RecordingVersion.UNKNOWN -> CoreCommonR.string.unimusic_sources_version_unknown
    RecordingVersion.STUDIO -> CoreCommonR.string.unimusic_sources_version_studio
    RecordingVersion.LIVE -> CoreCommonR.string.unimusic_sources_version_live
    RecordingVersion.COVER -> CoreCommonR.string.unimusic_sources_version_cover
    RecordingVersion.INSTRUMENTAL -> CoreCommonR.string.unimusic_sources_version_instrumental
    RecordingVersion.REMIX -> CoreCommonR.string.unimusic_sources_version_remix
    RecordingVersion.EDIT -> CoreCommonR.string.unimusic_sources_version_edit
    RecordingVersion.SPEED_ALTERED -> CoreCommonR.string.unimusic_sources_version_speed
    RecordingVersion.REMASTER -> CoreCommonR.string.unimusic_sources_version_remaster
    RecordingVersion.RERECORDING -> CoreCommonR.string.unimusic_sources_version_rerecording
}

private fun SourceChoiceNotice.messageRes(): Int = when (this) {
    SourceChoiceNotice.INCOMPLETE -> CoreCommonR.string.unimusic_sources_incomplete
    SourceChoiceNotice.OFFLINE -> CoreCommonR.string.unimusic_sources_offline
    SourceChoiceNotice.LOAD_FAILED -> CoreCommonR.string.unimusic_sources_load_failed
    SourceChoiceNotice.SAVE_FAILED -> CoreCommonR.string.unimusic_sources_save_failed
    SourceChoiceNotice.SAVED -> CoreCommonR.string.unimusic_sources_saved
    SourceChoiceNotice.CLEARED -> CoreCommonR.string.unimusic_sources_cleared
    SourceChoiceNotice.ACCOUNT_CHANGED -> CoreCommonR.string.unimusic_sources_account_changed
}
