package moe.ouom.neriplayer.platform.kuwo.api

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.unified.SourceCheckStatus
import moe.ouom.neriplayer.platform.qqmusic.api.QQMusicAudioClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit manual probe. No account, media body, password, cookie or signed URL is logged. */
class NativeCatalogNetworkSmokeTest {
    @Test fun normalClientsResolveFreeAudioAndPreserveAnonymousAccountDenial() = runBlocking {
        assumeTrue(System.getenv("UNIMUSIC_LIVE_NATIVE_SOURCES") == "true")
        val start = System.nanoTime()
        val kuwo = KuwoAudioClient().resolve("328578424")
        assertEquals(SourceCheckStatus.ADDRESS_RESOLVED, kuwo.status)
        assertNotNull(kuwo.url)
        assertTrue(kuwo.metadata!!.durationMs > 120_000)
        println("kuwo_api_resolution_ms=${(System.nanoTime() - start) / 1_000_000}")
        val qq = QQMusicAudioClient().resolve("003OUlho2HcRHC", null)
        assertEquals(SourceCheckStatus.AUTH_REQUIRED, qq.status)
        assertNull(qq.url)
    }
}
