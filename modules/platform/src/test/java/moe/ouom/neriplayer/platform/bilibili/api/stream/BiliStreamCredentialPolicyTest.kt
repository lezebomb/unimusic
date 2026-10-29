package moe.ouom.neriplayer.platform.bilibili.api.stream

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class BiliStreamCredentialPolicyTest {
    @Test fun savedCookiesUseOnlyActualEncryptedTrustedDestinations() {
        val original = linkedMapOf("cOoKiE" to "synthetic-old", "authorization" to "synthetic-auth", "Accept" to "audio/*")
        val trusted = buildBiliStreamRequestHeaders("https://upos.bilivideo.com/a.m4s", original, "synthetic-current")
        assertEquals("synthetic-current", trusted["Cookie"])
        assertEquals(1, trusted.keys.count { it.equals("cookie", true) })
        assertFalse(trusted.keys.any { it.equals("authorization", true) })
        val cleartext = buildBiliStreamRequestHeaders("http://mcdn.bilivideo.cn:8082/a.m4s", original, "synthetic-current")
        assertFalse(cleartext.keys.any { it.equals("cookie", true) || it.equals("authorization", true) })
        assertEquals("audio/*", cleartext["Accept"])
        for (url in listOf("https://bilivideo.com.attacker.example/a.m4s", "https://cdn.kuwo.cn/a.mp3")) {
            assertEquals(emptyMap<String, String>(), buildBiliStreamRequestHeaders(url, emptyMap(), "synthetic-current"))
        }
        assertEquals("synthetic-old", original["cOoKiE"])
    }

    @Test fun redirectHopsDropBiliCredentialsOutsideTrustedTlsWithoutChangingUnrelatedCalls() {
        fun sent(origin: String, destination: String): Request {
            val request = Request.Builder().url(destination).header("Cookie", "synthetic-cookie")
                .header("Cookie2", "synthetic-cookie2").header("Authorization", "synthetic-auth").header("Range", "bytes=0-3").build()
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
        val origin = "https://upos.bilivideo.com/a.m4s"
        for (destination in listOf("https://bilivideo.com.attacker.example/a", "http://upos.bilivideo.com/a")) {
            val result = sent(origin, destination)
            for (header in listOf("Cookie", "Cookie2", "Authorization")) assertNull(result.header(header))
            assertEquals("bytes=0-3", result.header("Range"))
        }
        assertEquals("synthetic-cookie", sent(origin, "https://backup.bilivideo.cn/a").header("Cookie"))
        assertEquals("synthetic-cookie", sent("https://cloud.example/a", "https://cloud.example/b").header("Cookie"))
    }
}
