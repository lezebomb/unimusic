package moe.ouom.neriplayer.ui

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import moe.ouom.neriplayer.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class NeriAppNavigationPresentationTest {
    @Test
    fun `outgoing navigation owners remain active until their transition finishes`() {
        val home = entry("home")
        val detail = entry("local_playlist_detail/{playlistId}")
        val lifecycleOwner = Any()
        val transition = resolveAppNavigationVisibility(listOf(home, detail), detail, lifecycleOwner)
        assertEquals(setOf(home, detail, lifecycleOwner), transition.owners)
        assertEquals(setOf("home", "local_playlist_detail/{playlistId}"), transition.routes)
        assertSame(detail.destination, transition.destination)
        assertEquals("local_playlist_detail/{playlistId}", transition.currentRoute)

        val settled = resolveAppNavigationVisibility(listOf(detail), detail, lifecycleOwner)
        assertFalse(settled.owners.contains(home))
        assertEquals(setOf("local_playlist_detail/{playlistId}"), settled.routes)
    }

    @Test
    fun `empty transition falls back to the current navigation owner`() {
        val home = entry("home")
        val lifecycleOwner = Any()
        val current = resolveAppNavigationVisibility(emptyList(), home, lifecycleOwner)
        assertEquals(setOf(home, lifecycleOwner), current.owners)
        assertEquals(setOf("home"), current.routes)

        val startup = resolveAppNavigationVisibility(emptyList(), null, lifecycleOwner)
        assertEquals(setOf(lifecycleOwner), startup.owners)
        assertEquals(setOf<String?>(null), startup.routes)
        assertNull(startup.destination)
        assertNull(startup.currentRoute)
    }

    @Test
    fun `only discover and mine are visible regardless of home preference`() {
        assertEquals(listOf(Destinations.Explore, Destinations.Library), appMainTabItems(false).map { it.first })
        assertEquals(listOf(Destinations.Home, Destinations.Library), appMainTabItems(true).map { it.first })
    }

    @Test
    fun `cover blur takes priority and audio analysis follows the dynamic background`() {
        assertEquals(NowPlayingBackgroundMode(false, false), resolveNowPlayingBackgroundMode(false, false, true))
        assertEquals(NowPlayingBackgroundMode(false, false), resolveNowPlayingBackgroundMode(true, true, true))
        assertEquals(NowPlayingBackgroundMode(false, false), resolveNowPlayingBackgroundMode(false, true, false))
        assertEquals(NowPlayingBackgroundMode(true, false), resolveNowPlayingBackgroundMode(true, false, false))
        assertEquals(NowPlayingBackgroundMode(true, true), resolveNowPlayingBackgroundMode(true, false, true))
    }

    private fun entry(route: String): NavBackStackEntry {
        val destination = mock(NavDestination::class.java)
        `when`(destination.route).thenReturn(route)
        return mock(NavBackStackEntry::class.java).also { `when`(it.destination).thenReturn(destination) }
    }
}
