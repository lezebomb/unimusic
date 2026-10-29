package moe.ouom.neriplayer.core.network.stream

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class BiliStreamCredentialInterceptorTest {
    @Test fun redirectHopsDropBiliCredentialsOutsideTrustedTlsWithoutChangingUnrelatedCalls() {
        val origin = "https://upos.bilivideo.com/a.m4s"
        for (destination in listOf("https://bilivideo.com.attacker.example/a", "http://upos.bilivideo.com/a")) {
            val result = sent(origin, destination)
            for (header in listOf("Cookie", "Cookie2", "Authorization")) assertNull(result.header(header))
            assertEquals("bytes=0-3", result.header("Range"))
        }
        assertEquals("synthetic-cookie", sent(origin, "https://backup.bilivideo.cn/a").header("Cookie"))
        assertEquals("synthetic-cookie", sent("https://cloud.example/a", "https://cloud.example/b").header("Cookie"))
    }

    @Test fun inheritedSegmentRequestsCannotForwardBiliCookiesToAnotherOrigin() {
        val child = "https://segments.example/audio.ts"
        for (headers in listOf(
            mapOf("Cookie" to "synthetic-device-id", "Referer" to "https://www.bilibili.com"),
            mapOf("Cookie" to "SESSDATA=synthetic-session"),
            mapOf("Cookie" to "buvid3=synthetic-device")
        )) {
            val result = sent(child, child, headers)
            for (header in listOf("Cookie", "Cookie2", "Authorization")) assertNull(result.header(header))
            assertEquals("bytes=0-3", result.header("Range"))
            assertEquals(headers["Cookie"], sent(child, "https://backup.bilivideo.com/audio.ts", headers).header("Cookie"))
        }
        val unrelated = mapOf("Cookie" to "custom=SESSDATA-is-a-value", "Referer" to "https://bilibili.com.other.example")
        assertEquals(unrelated["Cookie"], sent(child, child, unrelated).header("Cookie"))
    }

    private fun sent(origin: String, destination: String, extraHeaders: Map<String, String> = emptyMap()): Request {
        val request = Request.Builder().url(destination).header("Cookie", "synthetic-cookie")
            .header("Cookie2", "synthetic-cookie2").header("Authorization", "synthetic-auth").header("Range", "bytes=0-3")
            .apply { extraHeaders.forEach { (name, value) -> header(name, value) } }.build()
        val call = mock(Call::class.java)
        `when`(call.request()).thenReturn(Request.Builder().url(origin).build())
        val chain = mock(Interceptor.Chain::class.java)
        `when`(chain.call()).thenReturn(call)
        `when`(chain.request()).thenReturn(request)
        `when`(chain.proceed(any(Request::class.java) ?: request)).thenAnswer { invocation ->
            Response.Builder().request(invocation.getArgument(0)).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }
        return BiliStreamCredentialInterceptor().intercept(chain).use { it.request }
    }
}
