package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.unified.SourceCheckResult
import moe.ouom.neriplayer.data.model.unified.SourceCheckStatus

@Composable
internal fun SourceChoiceCheckResult(result: SourceCheckResult) {
    Text(stringResource(when (result.status) {
        SourceCheckStatus.ADDRESS_RESOLVED -> CoreCommonR.string.unimusic_sources_check_address
        SourceCheckStatus.LOCAL_READABLE -> CoreCommonR.string.unimusic_sources_check_local
        SourceCheckStatus.METADATA_ONLY -> CoreCommonR.string.unimusic_sources_metadata_only
        SourceCheckStatus.UNSUPPORTED -> CoreCommonR.string.unimusic_sources_check_unsupported
        SourceCheckStatus.DISABLED -> CoreCommonR.string.unimusic_sources_check_disabled
        SourceCheckStatus.OFFLINE -> CoreCommonR.string.unimusic_sources_check_offline
        SourceCheckStatus.USER_REJECTED -> CoreCommonR.string.unimusic_sources_check_rejected
        SourceCheckStatus.METADATA_MISMATCH -> CoreCommonR.string.unimusic_sources_check_mismatch
        SourceCheckStatus.AUTH_REQUIRED -> CoreCommonR.string.unimusic_sources_failure_login
        SourceCheckStatus.NO_PERMISSION -> CoreCommonR.string.unimusic_sources_check_permission
        SourceCheckStatus.PREVIEW_ONLY -> CoreCommonR.string.unimusic_sources_check_preview
        SourceCheckStatus.EXTERNAL_PLAYER_REQUIRED -> CoreCommonR.string.unimusic_sources_check_external
        SourceCheckStatus.NETWORK -> CoreCommonR.string.unimusic_sources_check_network
        SourceCheckStatus.TIMED_OUT -> CoreCommonR.string.unimusic_sources_failure_timeout
        SourceCheckStatus.UNAVAILABLE -> CoreCommonR.string.unimusic_sources_check_unavailable
        SourceCheckStatus.CONTEXT_CHANGED -> CoreCommonR.string.unimusic_sources_check_context
    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
