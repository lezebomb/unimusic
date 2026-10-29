package moe.ouom.neriplayer.ui.screen.tab

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.screen.tab.explore.ExploreSearchResultsList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28], application = Application::class)
class ExploreSearchResultsAnchorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backgroundRankingKeepsTheTopButPreservesAnActualUserScroll() {
        var rows by mutableStateOf((0..29).toList())
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            Box(Modifier.fillMaxWidth().height(240.dp).testTag("results")) {
                ExploreSearchResultsList(state, false, 0.dp, resultsIdentity = rows) {
                    items(rows, key = { it }) { Text("row $it", Modifier.fillMaxWidth().height(48.dp)) }
                }
            }
        }
        // A late layout anchor update is not a user's scroll gesture.
        compose.runOnIdle { state.requestScrollToItem(20) }
        compose.waitForIdle()
        compose.runOnIdle { rows = (1..29).toList() + 0 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(1, state.layoutInfo.visibleItemsInfo.first().key)
        }
        compose.onNodeWithTag("results").performTouchInput { swipeUp() }
        compose.waitForIdle()
        var anchor: Any? = null
        compose.runOnIdle {
            assertTrue(state.firstVisibleItemIndex > 0)
            anchor = state.layoutInfo.visibleItemsInfo.first().key
            rows = listOf(0) + rows.dropLast(1)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(anchor, state.layoutInfo.visibleItemsInfo.first().key) }
    }
}
