package moe.ouom.neriplayer.platform.gdstudio.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GdStudioClientTest {
    @Test fun exactIdentityRequestDoesNotSendCredentialsOrTrustSize() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("music-api.gdstudio.xyz", request.url.host)
            assertEquals("https", request.url.scheme)
            assertEquals(setOf("types", "source", "id", "br"), request.url.queryParameterNames)
            assertEquals("28815250", request.url.queryParameter("id"))
            assertEquals("netease", request.url.queryParameter("source"))
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"url":"https://m801.music.126.net/fixture.mp3","br":320,"size":12087507}""".toResponseBody()).build()
        }.build()
        val result = GdStudioClient(client).resolveNetease("28815250")!!
        assertEquals(320, result.bitrateKbps)
        assertEquals("audio/mpeg", result.mimeType)
    }

    @Test fun unavailableOrUnexpectedMediaIsRejected() {
        assertNull(parseGdStudioAudio("""{"url":"","br":0,"size":0}"""))
        for (url in listOf("http://m801.music.126.net/a.mp3", "https://localhost/a.mp3",
            "https://m801.music.126.net.evil.test/a.mp3", "https://user:secret@m801.music.126.net/a.mp3",
            "https://m801.music.126.net/a.m3u8", "https://m801.music.126.net/a.mp3#other")) {
            assertNull(parseGdStudioAudio("""{"url":"$url","br":320}"""))
        }
    }

    @Test fun rateLimitFailsFastAndRecoversWithoutAQueue() = runBlocking {
        var time = -1_000L
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"url":"","br":0}""".toResponseBody()).build()
        }.build()
        val api = GdStudioClient(client) { time }
        repeat(51) { assertNull(api.resolveNetease("28815250")) }
        assertEquals(50, calls)
        time += 300_000
        api.resolveNetease("28815250")
        assertEquals(51, calls)
    }
}
