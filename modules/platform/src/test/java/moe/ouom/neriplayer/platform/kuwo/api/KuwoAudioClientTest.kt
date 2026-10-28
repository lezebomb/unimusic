package moe.ouom.neriplayer.platform.kuwo.api

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
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

    @Test fun sessionRejectionRefreshesOnceAndRechecksPermission() = runBlocking {
        for (feeAfterRefresh in listOf(false, true)) {
            val paths = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                paths += request.url.encodedPath
                val n = paths.size
                if (n in listOf(1, 4)) assertNull(request.header("Cookie"))
                else assertEquals("$KUWO_SESSION_COOKIE=${if (n < 4) "old" else "new"}", request.header("Cookie"))
                val body = when (n) {
                    1, 4 -> "<html/>"
                    2 -> freeDetail
                    3 -> "rejected"
                    5 -> if (feeAfterRefresh) freeDetail.replace("false", "true") else freeDetail
                    6 -> playable
                    else -> error("Unexpected retry")
                }
                response(request, body, if (n == 3) 403 else 200,
                    if (n == 1) "old" else if (n == 4) "new" else null)
            }.build()
            val result = KuwoAudioClient(client).resolve("123")
            assertEquals(if (feeAfterRefresh) SourceCheckStatus.NO_PERMISSION else SourceCheckStatus.ADDRESS_RESOLVED, result.status)
            assertEquals(listOf("/", infoPath, playPath, "/", infoPath) + if (feeAfterRefresh) emptyList() else listOf(playPath), paths)
            if (feeAfterRefresh) assertNull(result.url)
        }
    }

    @Test fun sessionRetryIsBoundedAndOnlyForApiAuthenticationHttpFailure() = runBlocking {
        for (status in listOf(401, 403, 429, 500, 0)) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val root = chain.request().url.encodedPath == "/"
                if (!root && status == 0) throw IOException("fixture network failure")
                response(chain.request(), if (root) "<html/>" else "rejected", if (root) 200 else status,
                    if (root) "fixture" else null)
            }.build()
            try {
                KuwoAudioClient(client).resolve("123")
                fail("Expected transport rejection")
            } catch (_: IOException) { }
            assertEquals(if (status in listOf(401, 403)) 4 else 2, calls)
        }
        var roots = 0
        val rootRejected = OkHttpClient.Builder().addInterceptor { chain ->
            roots++; response(chain.request(), "rejected", 403)
        }.build()
        try { KuwoAudioClient(rootRejected).resolve("123"); fail("Expected root rejection") }
        catch (_: IOException) { }
        assertEquals(1, roots)
    }

    @Test fun sessionCancelledCallbackCannotAdoptOrOverwriteForegroundCookie() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val roots = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == "/" && roots.incrementAndGet() == 1) {
                val body = object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = -1L
                    override fun source() = object : Source {
                        val bytes = Buffer().writeUtf8("<html/>")
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            return bytes.read(sink, byteCount)
                        }
                        override fun timeout() = Timeout.NONE
                        override fun close() { closed.countDown() }
                    }.buffer()
                }
                response(request, "", cookie = "cancelled").newBuilder().body(body).build()
            } else {
                val root = request.url.encodedPath == "/"
                if (root) assertNull(request.header("Cookie"))
                else assertEquals("$KUWO_SESSION_COOKIE=foreground", request.header("Cookie"))
                response(request, if (root) "<html/>" else if (request.url.encodedPath == infoPath) freeDetail else playable,
                    cookie = if (root) "foreground" else null)
            }
        }.build()
        val api = KuwoAudioClient(client)
        val background = async(start = CoroutineStart.UNDISPATCHED) { api.resolve("123") }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            background.cancelAndJoin()
            assertEquals(SourceCheckStatus.ADDRESS_RESOLVED, withTimeout(2_000) { api.resolve("123") }.status)
            assertEquals(2, roots.get())
            release.countDown()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(SourceCheckStatus.ADDRESS_RESOLVED, api.resolve("123").status)
            assertEquals(2, roots.get())
        } finally { release.countDown(); background.cancelAndJoin() }
    }

    private fun response(request: okhttp3.Request, body: String, code: Int = 200, cookie: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .apply { if (cookie != null) header("Set-Cookie", "$KUWO_SESSION_COOKIE=$cookie; Path=/; Secure") }
            .body(body.toResponseBody()).build()

    private val infoPath = "/api/www/music/musicInfo"
    private val playPath = "/api/v1/www/music/playUrl"
    private val freeDetail = """{"code":200,"data":{"rid":123,"name":"Song","artist":"Artist","album":"Album","duration":200,"online":1,"isListenFee":false,"payInfo":{"cannotOnlinePlay":0}}}"""
    private val playable = """{"code":200,"data":{"url":"https://kw-lv.kuwo.cn/resource/fixture.mp3"}}"""

}
