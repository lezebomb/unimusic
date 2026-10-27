package moe.ouom.neriplayer.data.unified

import moe.ouom.neriplayer.data.model.unified.PlaylistImportMode
import moe.ouom.neriplayer.data.model.unified.PlaylistImportProgress
import moe.ouom.neriplayer.data.model.unified.PlaylistImportState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistImportAutoRefreshPolicyTest {
    @Test fun `refresh only completed linked imports and resume existing queued work`() {
        assertEquals(AutomaticImportAction.REFRESH, automaticImportAction(job(PlaylistImportState.COMPLETED)))
        listOf(PlaylistImportState.QUEUED, PlaylistImportState.RETRY_WAIT).forEach {
            assertEquals(AutomaticImportAction.RESUME, automaticImportAction(job(it)))
        }
        (PlaylistImportState.entries - setOf(PlaylistImportState.COMPLETED, PlaylistImportState.QUEUED, PlaylistImportState.RETRY_WAIT)).forEach {
            assertEquals(AutomaticImportAction.NONE, automaticImportAction(job(it)))
        }
    }

    @Test fun `one shot unlinked and other providers never become automatic work`() {
        val completed = job(PlaylistImportState.COMPLETED)
        assertEquals(AutomaticImportAction.NONE, automaticImportAction(completed.copy(mode = PlaylistImportMode.ONE_SHOT)))
        assertEquals(AutomaticImportAction.NONE, automaticImportAction(completed.copy(linked = false)))
        assertEquals(AutomaticImportAction.NONE, automaticImportAction(completed.copy(provider = "other")))
    }

    private fun job(state: PlaylistImportState) = PlaylistImportProgress(
        "job", "netease", "7", "10", "local", state, 3, 3, 0, 0, null, 0L,
        mode = PlaylistImportMode.LINKED, linked = true
    )
}
