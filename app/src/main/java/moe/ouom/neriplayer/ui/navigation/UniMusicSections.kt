package moe.ouom.neriplayer.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.haptic.HapticIconButton

/** Keep existing routes and saved page state while presenting two library sections. */
internal fun uniMusicSectionRoute(route: String?): String? = when (route) {
    Destinations.Home.route, Destinations.Explore.route -> Destinations.Home.route
    Destinations.Library.route, Destinations.Settings.route, Destinations.Debug.route -> Destinations.Library.route
    else -> null
}

internal fun uniMusicSectionParent(route: String, showHomeTab: Boolean): String? = when (route) {
    Destinations.Explore.route -> Destinations.Home.route.takeIf { showHomeTab }
    Destinations.Settings.route, Destinations.Debug.route -> Destinations.Library.route
    else -> null
}

internal fun uniMusicNavigationLabel(destination: Destinations): Int = when (uniMusicSectionRoute(destination.route)) {
    Destinations.Home.route -> CoreCommonR.string.unimusic_nav_discover
    Destinations.Library.route -> CoreCommonR.string.unimusic_nav_mine
    else -> destination.labelResId
}

internal fun isUniMusicNavigationDestinationSelected(current: NavDestination?, target: Destinations): Boolean {
    val section = uniMusicSectionRoute(target.route) ?: return false
    return current?.hierarchy?.any { uniMusicSectionRoute(it.route) == section } == true
}

internal fun NavHostController.navigateUniMusicSection(route: String, showHomeTab: Boolean) {
    val parent = uniMusicSectionParent(route, showHomeTab)
    val root = parent ?: route
    if (parent == null && currentDestination?.route != root &&
        uniMusicSectionRoute(currentDestination?.route) == uniMusicSectionRoute(root)) {
        returnToUniMusicSection(root)
        return
    }
    if (currentDestination?.route != root) {
        // Legacy startup preferences can start at a child. That entry may have been
        // removed on a previous return, so only pop to it while it is still present.
        val anchor = runCatching { getBackStackEntry(graph.startDestinationId).destination.id }
            .getOrDefault(graph.id)
        navigate(root) {
            popUpTo(anchor) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    if (parent != null && currentDestination?.route != route) {
        navigate(route) { launchSingleTop = true; restoreState = true }
    }
}

internal fun NavHostController.returnToUniMusicSection(root: String) {
    if (previousBackStackEntry?.destination?.route == root && popBackStack()) return
    // Handle a restored legacy child-only startup without leaving a back loop.
    navigate(root) {
        popUpTo(graph.id)
        launchSingleTop = true
    }
}

internal val LocalMainSectionBackAction = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun MainSectionBackButton() {
    val action = LocalMainSectionBackAction.current ?: return
    HapticIconButton(onClick = action) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(CoreCommonR.string.action_back))
    }
}
