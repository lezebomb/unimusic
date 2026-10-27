package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.viewmodel.tab.home.HomeLocalRecommendationsState

@Composable
internal fun LocalRecommendationPreferencesDialog(
    state: HomeLocalRecommendationsState,
    onDismiss: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.unimusic_feedback_preferences)) },
        text = { Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(CoreCommonR.string.unimusic_feedback_enabled), Modifier.weight(1f))
                Switch(checked = state.recommendations.playbackFeedbackEnabled,
                    onCheckedChange = onSetEnabled, enabled = !state.loading && state.recommendations.feedbackAvailable)
            }
            Text(stringResource(CoreCommonR.string.unimusic_feedback_privacy))
            TextButton(onClick = onClear, enabled = !state.loading && state.recommendations.feedbackAvailable) {
                Text(stringResource(CoreCommonR.string.unimusic_feedback_clear))
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) {
            Text(stringResource(CoreCommonR.string.unimusic_feedback_done))
        } }
    )
}
