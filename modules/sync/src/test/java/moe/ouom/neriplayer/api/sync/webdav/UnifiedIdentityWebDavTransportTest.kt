package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.unified.sync.UnifiedIdentityDataset
import moe.ouom.neriplayer.data.sync.unified.UnifiedIdentityCodec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class UnifiedIdentityWebDavTransportTest {
    private val wire = UnifiedIdentityCodec.encode(UnifiedIdentityDataset())
    private data class Reply(val status: Int, val body: ByteArray = byteArrayOf(), val etag: String? = null)
    private class Script(vararg replies: Reply) {
        val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request();requests.add(request)
            val reply = queue.removeFirst()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.status).message("fixture")
                .body(reply.body.toResponseBody()).apply { reply.etag?.let { header("ETag", it) } }.build()
        }.build()
        fun transport() = UnifiedIdentityWebDavTransport("https://example.test/dav/neriplayer-sync.json", "fixture", "fixture", client)
    }

    @Test fun `missing file creates conditionally and existing file preserves the strong validator`() = runTest {
        val missing = Script(Reply(404), Reply(201))
        val api = missing.transport()
        val snapshot = api.read()
        assertNull(snapshot.bytes)
        assertTrue(api.compareAndSet(snapshot.version, wire))
        val request = missing.requests.last()
        assertEquals("/dav/unimusic-identities-v1.json", request.url.encodedPath)
        assertEquals("PUT", request.method);assertEquals("*", request.header("If-None-Match"));assertNull(request.header("If-Match"))
        assertTrue(request.header("Authorization")!!.startsWith("Basic "))
        val existing = Script(Reply(200, wire, "\"v1\""), Reply(412))
        val current = existing.transport();val read = current.read()
        assertArrayEquals(wire, read.bytes)
        assertFalse(current.compareAndSet(read.version, wire))
        assertEquals("\"v1\"", existing.requests.last().header("If-Match"))
        assertNull(existing.requests.last().header("If-None-Match"))
    }

    @Test fun `weak validators unsafe endpoint fields and server error bodies never cause an unconditional fallback`() = runTest {
        val weak = Script(Reply(200, wire, "W/\"v1\""))
        try { weak.transport().read();fail("Expected validator refusal") } catch (_: IOException) { }
        assertEquals(1, weak.requests.size)
        val failure = Script(Reply(401, "private-server-cookie".toByteArray()))
        try { failure.transport().read();fail("Expected authentication failure") }
        catch (error: UnifiedIdentityWebDavException) {
            assertEquals(401, error.statusCode);assertFalse(error.message!!.contains("private-server-cookie"))
        }
        val noRequests = Script()
        for (url in listOf("https://user:private@example.test/sync", "https://example.test/sync?token=private", "https://example.test/sync#private")) {
            try { UnifiedIdentityWebDavTransport(url, "fixture", "fixture", noRequests.client);fail("Unsafe endpoint") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(noRequests.requests.isEmpty())
    }
}
