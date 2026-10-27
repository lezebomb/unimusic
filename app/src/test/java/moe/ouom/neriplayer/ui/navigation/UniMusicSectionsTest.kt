package moe.ouom.neriplayer.ui.navigation

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import moe.ouom.neriplayer.navigation.Destinations
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UniMusicSectionsTest {
    @Test
    fun `search and settings retain their parent and return without looping`() {
        val nav = controller("home")
        nav.navigateUniMusicSection("explore", true)
        assertEquals("home", nav.previousBackStackEntry?.destination?.route)
        nav.returnToUniMusicSection("home")
        assertEquals("home", nav.currentDestination?.route)
        nav.navigateUniMusicSection("settings", true)
        assertEquals("library", nav.previousBackStackEntry?.destination?.route)
        nav.returnToUniMusicSection("library")
        assertEquals("library", nav.currentDestination?.route)
        nav.navigateUniMusicSection("home", true)
        assertEquals("home", nav.currentDestination?.route)
    }

    @Test
    fun `legacy child startup can return and switch sections after its entry is removed`() {
        val nav = controller("settings")
        nav.returnToUniMusicSection("library")
        assertEquals("library", nav.currentDestination?.route)
        assertNull(nav.previousBackStackEntry)
        nav.navigateUniMusicSection("home", true)
        nav.navigateUniMusicSection("settings", true)
        assertEquals("settings", nav.currentDestination?.route)
        assertEquals("library", nav.previousBackStackEntry?.destination?.route)
        nav.navigateUniMusicSection("library", true)
        assertEquals("library", nav.currentDestination?.route)
    }

    @Test
    fun `disabled home uses search as the discover root and selects only its section`() {
        val nav = controller("explore")
        assertNull(uniMusicSectionParent("explore", false))
        assertTrue(isUniMusicNavigationDestinationSelected(nav.currentDestination, Destinations.Explore))
        assertFalse(isUniMusicNavigationDestinationSelected(nav.currentDestination, Destinations.Library))
        nav.navigateUniMusicSection("debug", false)
        assertEquals("library", nav.previousBackStackEntry?.destination?.route)
        assertTrue(isUniMusicNavigationDestinationSelected(nav.currentDestination, Destinations.Library))
        assertFalse(isUniMusicNavigationDestinationSelected(nav.currentDestination, Destinations.Explore))
    }

    private fun controller(start: String): NavHostController {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return NavHostController(activity).apply {
            setLifecycleOwner(activity)
            setViewModelStore(ViewModelStore())
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = start) {
                listOf("home", "explore", "library", "settings", "debug").forEach { route ->
                    composable(route) { }
                }
            }
        }
    }
}
