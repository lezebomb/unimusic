package moe.ouom.neriplayer.platform.kuwo.api

import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.network.http.awaitResponse
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.unified.SourceCheckStatus
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class KuwoAudioClientTest {
    /** Manual diagnostic, skipped in ordinary CI. Never prints raw responses, cookies or signed URLs. */
    @Test fun optInDesktopKuwoTransportProbe() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("UNIMUSIC_LIVE_KUWO_PROBE") == "1")
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        try {
            withTimeout(16_000) {
                val result = KuwoAudioClient().resolve("328578424")
                println("UNIMUSIC_PROBE provider=kuwo stage=api status=${result.status} elapsed_ms=${elapsed()}")
                if (result.status != SourceCheckStatus.ADDRESS_RESOLVED) {
                    assertTrue("Rejected resolution unexpectedly carried an address", result.url == null)
                    return@withTimeout
                }
                val stream = OkHttpClient.Builder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true).followSslRedirects(false).build()
                val request = okhttp3.Request.Builder().url(requireNotNull(result.url))
                    .header("Range", "bytes=0-8191").header("Referer", "https://www.kuwo.cn/").build()
                stream.newCall(request).awaitResponse { response ->
                    println("UNIMUSIC_PROBE provider=kuwo stage=media http=${response.code} elapsed_ms=${elapsed()}")
                    assertTrue("Media HTTP response failed", response.isSuccessful)
                    val prefix = response.body.byteStream().readNBytes(8192)
                    val mime = response.body.contentType()?.toString().orEmpty()
                    val id3 = prefix.size >= 3 && prefix[0] == 0x49.toByte() && prefix[1] == 0x44.toByte() && prefix[2] == 0x33.toByte()
                    val sync = (0 until (prefix.size - 1).coerceAtLeast(0)).any { i ->
                        prefix[i].toInt() and 0xff == 0xff && prefix[i + 1].toInt() and 0xe0 == 0xe0 }
                    val audio = mime.startsWith("audio/") || id3 || sync
                    println("UNIMUSIC_PROBE provider=kuwo stage=prefix bytes=${prefix.size} audio_header=$audio elapsed_ms=${elapsed()}")
                    assertTrue("Media prefix was not recognizable audio", prefix.isNotEmpty() && audio)
                }
            }
        } catch (error: Exception) {
            // Diagnostic exceptions must not carry a signed media URL or a response body into JUnit reports.
            println("UNIMUSIC_PROBE provider=kuwo stage=failed type=${error.javaClass.simpleName} elapsed_ms=${elapsed()}")
            throw AssertionError("Kuwo transport diagnostic failed: ${error.javaClass.simpleName}")
        }
    }

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
