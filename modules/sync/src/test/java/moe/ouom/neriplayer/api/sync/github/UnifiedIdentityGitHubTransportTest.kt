package moe.ouom.neriplayer.api.sync.github

import com.google.gson.JsonParser
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.unified.sync.UnifiedIdentityDataset
import moe.ouom.neriplayer.data.sync.unified.UnifiedIdentityCodec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class UnifiedIdentityGitHubTransportTest {
    private val wire = UnifiedIdentityCodec.encode(UnifiedIdentityDataset())
    private val head = "a".repeat(40)
    private val baseTree = "b".repeat(40)
    private val tree = "c".repeat(40)
    private val commit = "d".repeat(40)
    private val branch = "feature/identities"
    private fun blob() = MessageDigest.getInstance("SHA-1").digest(
        ("blob ${wire.size}" + 0.toChar()).toByteArray() + wire).joinToString("") { "%02x".format(it) }
    private data class Reply(val status: Int, val body: String = "")
    private class Script(replies: List<Reply>) {
        val replies = ArrayDeque(replies)
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request();requests.add(request)
            val reply = this.replies.removeFirst()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.status).message("fixture")
                .body(reply.body.toResponseBody()).build()
        }.build()
        fun api(owner: String = "fixture") = UnifiedIdentityGitHubTransport(owner, "backup", "fixture-token", client)
    }
    private fun script(stale: Boolean = false, wrongBlob: Boolean = false): Script = Script(listOf(
        Reply(200, "{\"default_branch\":\"$branch\",\"node_id\":\"R_fixture\"}"),
        Reply(200, "{\"name\":\"$branch\",\"commit\":{\"sha\":\"$head\",\"commit\":{\"tree\":{\"sha\":\"$baseTree\"}}}}"),
        Reply(404), Reply(201, "{\"sha\":\"${if (wrongBlob) "f".repeat(40) else blob()}\"}"),
        Reply(201, "{\"sha\":\"$tree\"}"), Reply(201, "{\"sha\":\"$commit\"}"),
        Reply(200, if (stale) "{\"errors\":[{\"type\":\"STALE_DATA\",\"message\":\"private-server-text\"}]}"
            else "{\"data\":{\"updateRefs\":{\"clientMutationId\":\"$commit\"}}}")
    ))
    private fun body(request: Request) = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject

    @Test fun `raw read pins the commit and publication preserves the base tree with an exact atomic ref condition`() = runTest {
        val fixture = script()
        val api = fixture.api()
        val read = api.read()
        assertNull(read.bytes)
        assertEquals(head, fixture.requests[2].url.queryParameter("ref"))
        assertEquals("application/vnd.github.raw+json", fixture.requests[2].header("Accept"))
        assertTrue(fixture.requests[1].url.encodedPath.contains("feature%2Fidentities"))
        assertTrue(api.compareAndSet(read.version, wire))
        assertEquals(7, fixture.requests.size)
        val createdTree = body(fixture.requests[4])
        assertEquals(baseTree, createdTree.get("base_tree").asString)
        val entries = createdTree.getAsJsonArray("tree")
        assertEquals(1, entries.size())
        assertEquals("unimusic-identities-v1.json", entries[0].asJsonObject.get("path").asString)
        assertEquals(blob(), entries[0].asJsonObject.get("sha").asString)
        assertEquals(head, body(fixture.requests[5]).getAsJsonArray("parents")[0].asString)
        val input = body(fixture.requests[6]).getAsJsonObject("variables").getAsJsonObject("input")
        assertEquals("R_fixture", input.get("repositoryId").asString)
        val update = input.getAsJsonArray("refUpdates")[0].asJsonObject
        assertEquals(head, update.get("beforeOid").asString)
        assertEquals(commit, update.get("afterOid").asString)
        assertFalse(update.get("force").asBoolean)
        assertTrue(fixture.requests.all { it.header("Authorization")!!.startsWith("Bearer ") })
    }

    @Test fun `stale refs never force publish and invalid blob acknowledgements or foreign versions stop early`() = runTest {
        val stale = script(stale = true)
        val api = stale.api();val snapshot = api.read()
        assertFalse(api.compareAndSet(snapshot.version, wire))
        assertEquals(7, stale.requests.size)
        assertFalse(stale.requests.any { it.method == "PATCH" || it.method == "DELETE" })
        val wrong = script(wrongBlob = true)
        val wrongApi = wrong.api();val wrongSnapshot = wrongApi.read()
        try { wrongApi.compareAndSet(wrongSnapshot.version, wire);fail("Wrong blob accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(4, wrong.requests.size)
        val foreign = Script(emptyList())
        try { foreign.api("other").compareAndSet(snapshot.version, wire);fail("Foreign repository version accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(foreign.requests.isEmpty())
    }
}
