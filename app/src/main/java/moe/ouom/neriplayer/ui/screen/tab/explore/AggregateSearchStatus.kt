package moe.ouom.neriplayer.ui.screen.tab.explore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.search.FederatedSearchSnapshot
import moe.ouom.neriplayer.common.search.SearchSourceFailure
import moe.ouom.neriplayer.ui.viewmodel.tab.ExploreSearchResult

@Composable
internal fun AggregateSearchStatus(snapshot: FederatedSearchSnapshot<ExploreSearchResult>?, onRetry: () -> Unit) {
    if (snapshot == null) return
    val names = mapOf("local" to stringResource(CoreCommonR.string.local_files),
        "netease" to stringResource(CoreCommonR.string.platform_netease_short),
        "bilibili" to stringResource(CoreCommonR.string.platform_bilibili), "youtube" to "YouTube",
        "kuwo" to stringResource(CoreCommonR.string.explore_catalog_kuwo),
        "qqmusic" to "QQ 音乐")
    val authRequired = stringResource(CoreCommonR.string.explore_source_login)
    val disabled = stringResource(CoreCommonR.string.explore_source_disabled)
    val unavailable = stringResource(CoreCommonR.string.explore_source_unavailable)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(snapshot.sources.entries.joinToString(" · ") { (id, state) ->
            val name = names[id] ?: id
            val suffix = when {
                state.loading -> "…"
                state.failure == SearchSourceFailure.AUTH_REQUIRED -> authRequired
                state.failure == SearchSourceFailure.DISABLED -> disabled
                state.failure != null -> unavailable
                else -> state.items.size.toString()
            }
            "$name $suffix"
        }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (snapshot.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        if (!snapshot.loading && snapshot.sources.values.any { it.failure != null && it.nextCursor != null }) {
            androidx.compose.material3.TextButton(onClick = onRetry) { Text(stringResource(CoreCommonR.string.explore_aggregate_retry)) }
        }
    }
}
