package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.recommendation.LocalRecommendation
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.viewmodel.tab.HomeViewModel

internal data class HomeRecommendationFeedbackActions(
    val dismiss: (LocalRecommendation) -> Unit,
    val reset: () -> Unit
)

@Composable
internal fun rememberRecommendationFeedbackActions(vm: HomeViewModel, host: SnackbarHostState): HomeRecommendationFeedbackActions {
    val scope = rememberCoroutineScope()
    val hidden = stringResource(CoreCommonR.string.unimusic_recommendations_dismissed)
    val undo = stringResource(CoreCommonR.string.unimusic_recommendations_undo)
    val restored = stringResource(CoreCommonR.string.unimusic_recommendations_restored)
    val error = stringResource(CoreCommonR.string.unimusic_recommendations_feedback_error)
    return remember(vm, host, scope, hidden, undo, restored, error) {
        HomeRecommendationFeedbackActions(
            dismiss = { item -> scope.launch {
                try {
                    val token = vm.dismissLocalRecommendation(item)
                    host.currentSnackbarData?.dismiss()
                    if (host.showSnackbar(hidden, undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                        vm.undoLocalRecommendationDismissal(token)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    host.showNeriSnackbar(error)
                }
            }; Unit },
            reset = { scope.launch {
                try {
                    vm.resetLocalRecommendationFeedback()
                    host.currentSnackbarData?.dismiss()
                    host.showNeriSnackbar(restored)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    host.showNeriSnackbar(error)
                }
            }; Unit }
        )
    }
}
