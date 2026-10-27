package moe.ouom.neriplayer.platform.kuwo.api

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.unified.SourceCheckStatus
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class KuwoAudioClientTest {
    @Test fun webHeaderMatchesOfficialJavascriptAndRotatingSession() = runBlocking {
        assertEquals("423ee4cc684a075ead08c6f833cec45f6de285b85c9ff200bc614e",
            kuwoWebSessionHeader("anonymous-fixture-token", 12_345_678))
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("www.kuwo.cn", request.url.host)
            val body = when (++calls) {
                1 -> "<html/>"
                2 -> {
                    assertEquals("$KUWO_SESSION_COOKIE=first-token", request.header("Cookie"))
                    assertNotNull(request.header("Secret"))
                    """{"code":200,"data":{"rid":123,"name":"Song","artist":"Artist","album":"Album","duration":200,"online":1,"isListenFee":false,"payInfo":{"cannotOnlinePlay":0}}}"""
                }
                else -> {
                    assertEquals("$KUWO_SESSION_COOKIE=second-token", request.header("Cookie"))
                    """{"code":200,"data":{"url":"https://kw-lv.kuwo.cn/resource/fixture.mp3"}}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Set-Cookie", "$KUWO_SESSION_COOKIE=${if (calls == 1) "first-token" else "second-token"}; Path=/; Secure")
                .body(body.toResponseBody()).build()
        }.build()
        val result = KuwoAudioClient(client).resolve("123")
        assertEquals(SourceCheckStatus.ADDRESS_RESOLVED, result.status)
        assertEquals(200_000L, result.metadata!!.durationMs)
        assertEquals(3, calls)
    }

    @Test fun feeOrMissingPermissionNeverRequestsAudio() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertFalse(chain.request().url.encodedPath.contains("playUrl"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Set-Cookie", "$KUWO_SESSION_COOKIE=token; Path=/; Secure")
                .body((if (calls == 1) "<html/>" else """{"code":200,"data":{"rid":123,"name":"Song","artist":"Artist","duration":200,"online":1,"isListenFee":true,"payInfo":{"cannotOnlinePlay":0}}}""").toResponseBody()).build()
        }.build()
        assertEquals(SourceCheckStatus.NO_PERMISSION, KuwoAudioClient(client).resolve("123").status)
        assertEquals(2, calls)
    }
}
