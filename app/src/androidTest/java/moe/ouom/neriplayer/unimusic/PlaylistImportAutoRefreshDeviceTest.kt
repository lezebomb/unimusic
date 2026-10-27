package moe.ouom.neriplayer.unimusic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.unified.PlaylistImportAutoRefresh
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistImportAutoRefreshDeviceTest {
    @Test fun schedulesOnceAndDisablesWithoutImporting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = PlaylistImportAutoRefresh(context)
        val manager = WorkManager.getInstance(context)
        val original = settings.isEnabled()
        try {
            settings.setEnabled(true)
            settings.reconcile()
            val active = manager.getWorkInfosForUniqueWork(PlaylistImportAutoRefresh.WORK_NAME)
                .get(15, TimeUnit.SECONDS).filterNot { it.state.isFinished }
            assertTrue(settings.isEnabled())
            assertEquals(1, active.size)
            assertTrue(PlaylistImportAutoRefresh.WORK_NAME in active.single().tags)
            settings.setEnabled(false)
            assertFalse(settings.isEnabled())
            assertTrue(manager.getWorkInfosForUniqueWork(PlaylistImportAutoRefresh.WORK_NAME)
                .get(15, TimeUnit.SECONDS).all { it.state.isFinished })
        } finally {
            settings.setEnabled(original) // Restore user preference, never import or change a playlist.
        }
    }
}
