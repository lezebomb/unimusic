package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.model.unified.ImportFailureKind
import moe.ouom.neriplayer.data.model.unified.PlaylistImportProgress
import moe.ouom.neriplayer.data.model.unified.PlaylistImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportUiPolicyTest {
    @Test
    fun `public account input becomes a canonical positive ID only`() {
        assertEquals("12345", normalizeImportAccount(" 0012345 "))
        listOf("", "0", "-1", "+1", "1.5", "https://music.163.com/user/home?id=1",
            "MUSIC_U=secret", "9223372036854775808").forEach { input ->
            assertNull("Invalid input: $input", normalizeImportAccount(input))
        }
    }

    @Test
    fun `only pending running and paused work can be cancelled`() {
        val active = setOf(PlaylistImportState.QUEUED, PlaylistImportState.RUNNING,
            PlaylistImportState.RETRY_WAIT, PlaylistImportState.PAUSED_AUTH)
        PlaylistImportState.entries.forEach { state -> assertEquals(state in active, canCancelImport(state)) }
    }

    @Test
    fun `retry offers recovery for pauses but never recreates a removed target`() {
        assertTrue(canRetryImport(job(PlaylistImportState.PAUSED_AUTH, ImportFailureKind.AUTH_REQUIRED)))
        assertTrue(canRetryImport(job(PlaylistImportState.PARTIAL_FAILED)))
        assertTrue(canRetryImport(job(PlaylistImportState.CANCELLED)))
        assertFalse(canRetryImport(job(PlaylistImportState.CANCELLED, ImportFailureKind.TARGET_REMOVED)))
        assertFalse(canRetryImport(job(PlaylistImportState.RUNNING)))
        assertFalse(canRetryImport(job(PlaylistImportState.COMPLETED)))
    }

    private fun job(state: PlaylistImportState, error: ImportFailureKind? = null) = PlaylistImportProgress(
        jobId = "job", provider = "netease", accountKey = "1", remotePlaylistId = "2", playlistId = null,
        state = state, total = 1, imported = 0, failed = 0, skipped = 0, lastError = error, retryAt = 0
    )
}
