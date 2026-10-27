package moe.ouom.neriplayer.platform.gdstudio.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Manual, opt-in public API probe. Never requests or saves the audio body. */
class GdStudioNetworkSmokeTest {
    @Test fun resolvesExactIdWhenExplicitlyEnabled() = runBlocking {
        val id = System.getenv("UNIMUSIC_LIVE_GD_NETEASE_ID")
        assumeTrue(!id.isNullOrBlank())
        val audio = GdStudioClient().resolveNetease(requireNotNull(id))
        assertNotNull("Public API did not return an accepted MP3 address", audio)
        assertEquals("audio/mpeg", audio!!.mimeType)
        assertTrue(audio.bitrateKbps > 0)
    }
}
