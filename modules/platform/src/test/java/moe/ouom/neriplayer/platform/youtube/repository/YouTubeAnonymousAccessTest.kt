package moe.ouom.neriplayer.platform.youtube.repository

import java.util.Collections
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.youtube.api.playback.YouTubePlaybackDeniedException
import moe.ouom.neriplayer.platform.youtube.api.playback.YouTubePlaybackDenialReason
import moe.ouom.neriplayer.platform.youtube.api.playback.terminalAnonymousPlaybackDenial
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class YouTubeAnonymousAccessTest {
    @Test fun anonymousDenialStopsAfterOneClientSweepWithoutRestartingBootstrap() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<okhttp3.Request>())
        val html = """<script>ytcfg.set({"INNERTUBE_API_KEY":"test-key","INNERTUBE_CLIENT_VERSION":"1.test","VISITOR_DATA":"test-visitor","jsUrl":"/s/player/test/base.js","STS":20529});</script>"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = if (request.url.encodedPath == "/") html
                else """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val repository = YouTubeMusicPlaybackRepository(client)
        try {
            val failure = runCatching { repository.getBestPlayableAudio("public-test-id") }.exceptionOrNull()
            assertTrue(failure is YouTubePlaybackDeniedException)
            assertTrue((failure as YouTubePlaybackDeniedException).requiresLogin)
            val copy = synchronized(requests) { requests.toList() }
            assertEquals(1, copy.count { it.url.encodedPath == "/" })
            val player = copy.filter { it.url.encodedPath.contains("/youtubei/v1/player") }
            assertTrue(player.isNotEmpty())
            assertEquals(player.size, player.map { it.header("X-YouTube-Client-Name") to it.header("X-YouTube-Client-Version") }.distinct().size)
            assertFalse(copy.any { it.url.encodedPath == "/watch" })
        } finally {
            repository.clearAuthBoundCaches()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun authenticatedUncertainAndPartlyPlayableSweepsRetainRecovery() {
        val denied = setOf("LOGIN_REQUIRED")
        assertNull(terminalAnonymousPlaybackDenial(denied, true, false, false))
        assertNull(terminalAnonymousPlaybackDenial(denied, false, true, false))
        assertNull(terminalAnonymousPlaybackDenial(denied, false, false, true))
        assertNull(terminalAnonymousPlaybackDenial(setOf("ERROR"), false, false, false))
        assertNull(terminalAnonymousPlaybackDenial(emptySet(), false, false, false))
    }

    @Test fun contentUnavailableAndSignInDecisionsStayDistinct() {
        assertEquals(YouTubePlaybackDenialReason.UNPLAYABLE,
            terminalAnonymousPlaybackDenial(setOf("UNPLAYABLE"), false, false, false))
        assertEquals(YouTubePlaybackDenialReason.LOGIN_REQUIRED,
            terminalAnonymousPlaybackDenial(setOf("UNPLAYABLE", "LOGIN_REQUIRED"), false, false, false))
    }
}
