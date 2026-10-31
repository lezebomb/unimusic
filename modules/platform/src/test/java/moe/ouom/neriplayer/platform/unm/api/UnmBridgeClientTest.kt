package moe.ouom.neriplayer.platform.unm.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnmBridgeClientTest {
    private val bridge = "http://192.168.1.20:8900"

    @Test
    fun matchSendsExactIdAndTrustsOnlySizeAndBitrate() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("http", request.url.scheme)
            assertEquals("192.168.1.20", request.url.host)
            assertEquals(8900, request.url.port)
            assertEquals("/match", request.url.encodedPath)
            assertEquals(setOf("id"), request.url.queryParameterNames)
            assertEquals("2142857204", request.url.queryParameter("id"))
            // No `sources` parameter: the bridge applies its own 320 kb/s-first tier order.
            assertNull(request.url.queryParameter("sources"))
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    (
                        """{"ok":true,"id":"2142857204","source":"bodian","size":7373020,"br":320000,""" +
                            """"url":"http://bd-er.kuwo.cn/7c14/resource/30106/trackmedia/M800000ONJFa3jViPd.mp3?from=bodian",""" +
                            """"md5":null}"""
                        ).toResponseBody()
                ).build()
        }.build()

        val outcome = UnmBridgeClient(client).match(bridge, "2142857204", expectedDurationMs = 184_243L)

        val audio = (outcome as UnmBridgeOutcome.Matched).audio
        assertEquals("bodian", audio.source)
        assertEquals(320, audio.bitrateKbps)
        assertEquals(7_373_020L, audio.sizeBytes)
        // 7373020 bytes at 320 kb/s is 184.3 s: derived from the reported size, never from a field we trust.
        assertEquals(184_325L, audio.durationMs)
        assertEquals("audio/mpeg", audio.mimeType)
        assertNull(audio.md5)
    }

    @Test
    fun truncatedOrMismatchedStreamIsRejected() {
        // `bilivideo` observed size for a 184 s track was ~1.05 MB; the duration gate must catch it even
        // when a bridge is misconfigured to allow that provider.
        assertEquals(
            UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED),
            parseUnmBridgeMatch(
                """{"ok":true,"id":"2142857204","source":"bilivideo","size":1050000,"br":320000,"url":"https://upos-sz-mirrorcos.bilivideo.com/a.m4a"}""",
                expectedId = "2142857204",
                expectedDurationMs = 184_243L
            )
        )
        assertEquals(
            UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED),
            parseUnmBridgeMatch(
                """{"ok":true,"id":"2142857204","source":"pyncmd","size":1050000,"br":320000,"url":"https://m701.music.126.net/a.mp3"}""",
                expectedId = "2142857204",
                expectedDurationMs = 184_243L
            )
        )
    }

    @Test
    fun unexpectedProvidersAndAddressesAreRejected() {
        val body = { source: String, url: String ->
            """{"ok":true,"id":"2142857204","source":"$source","size":7373020,"br":320000,"url":"$url"}"""
        }
        for (source in listOf("bilibili", "ytdlp", "youtube", "migu", "unknown")) {
            assertEquals(
                UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED),
                parseUnmBridgeMatch(body(source, "https://m701.music.126.net/a.mp3"), "2142857204", null)
            )
        }
        for (url in listOf(
            "http://m701.music.126.net/a.mp3", // NetEase CDN is https-only
            "https://localhost/a.mp3",
            "https://m701.music.126.net.evil.test/a.mp3",
            "https://user:secret@m701.music.126.net/a.mp3",
            "https://m701.music.126.net/a.m3u8",
            "https://m701.music.126.net/a.mp3#other"
        )) {
            assertEquals(
                "rejects $url",
                UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED),
                parseUnmBridgeMatch(body("pyncmd", url), "2142857204", null)
            )
        }
    }

    @Test
    fun identityEchoAndBitrateAreEnforced() {
        assertEquals(
            UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED),
            parseUnmBridgeMatch(
                """{"ok":true,"id":"2683843127","source":"pyncmd","size":7373020,"br":320000,"url":"https://m701.music.126.net/a.mp3"}""",
                expectedId = "2142857204",
                expectedDurationMs = null
            )
        )
        assertEquals(
            UnmBridgeOutcome.Failed(UnmBridgeFailure.REJECTED),
            parseUnmBridgeMatch(
                """{"ok":true,"id":"2142857204","source":"pyncmd","size":7373020,"br":999,"url":"https://m701.music.126.net/a.mp3"}""",
                expectedId = "2142857204",
                expectedDurationMs = null
            )
        )
    }

    @Test
    fun missingServiceAndRefusalsStayDistinguishable() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val code = if (chain.request().url.queryParameter("id") == "1") 404 else 503
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body("""{"ok":false,"reason":"not_found"}""".toResponseBody()).build()
        }.build()
        val api = UnmBridgeClient(client)
        assertEquals(
            UnmBridgeOutcome.Failed(UnmBridgeFailure.NOT_FOUND),
            api.match(bridge, "1")
        )
        assertEquals(
            UnmBridgeOutcome.Failed(UnmBridgeFailure.UNREACHABLE),
            api.match(bridge, "2")
        )
        assertEquals(
            UnmBridgeOutcome.Failed(UnmBridgeFailure.UNCONFIGURED),
            api.match("   ", "3")
        )
        assertEquals(
            UnmBridgeOutcome.Failed(UnmBridgeFailure.UNCONFIGURED),
            api.match("file:///tmp/bridge.sock", "4")
        )
        // ok:false payloads are refusals, not transport failures.
        assertEquals(
            UnmBridgeOutcome.Failed(UnmBridgeFailure.NOT_FOUND),
            parseUnmBridgeMatch("""{"ok":false,"reason":"song_not_available"}""", "5", null)
        )
    }

    @Test
    fun rateLimitFailsFastAndRecoversWithoutAQueue() = runBlocking {
        var time = -1_000L
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"ok":false}""".toResponseBody()).build()
        }.build()
        val api = UnmBridgeClient(client) { time }
        repeat(61) { api.match(bridge, "2142857204") }
        assertEquals(60, calls)
        assertTrue((api.match(bridge, "2142857204") as UnmBridgeOutcome.Failed).reason == UnmBridgeFailure.UNREACHABLE)
        time += 300_000
        api.match(bridge, "2142857204")
        assertEquals(61, calls)
    }

    @Test
    fun explicitSourcesKeepOnlySupportedProviders() = runBlocking {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request().url.queryParameter("sources") ?: "<none>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"ok":false}""".toResponseBody()).build()
        }.build()
        val api = UnmBridgeClient(client)
        api.match(bridge, "2142857204", sources = listOf("bodian", "bilivideo", "kuwo"))
        api.match(bridge, "2142857204", sources = listOf("bilivideo", "ytdlp"))
        assertEquals(listOf("bodian,kuwo", "<none>"), seen)
    }

    @Test
    fun probeReportsBridgeVersion() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/health", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"ok":true,"version":"0.1.0"}""".toResponseBody()).build()
        }.build()
        assertEquals("0.1.0", UnmBridgeClient(client).probe("http://192.168.1.20:8900/"))
        assertNull(UnmBridgeClient(client).probe("192.168.1.20:8900"))
    }
}
