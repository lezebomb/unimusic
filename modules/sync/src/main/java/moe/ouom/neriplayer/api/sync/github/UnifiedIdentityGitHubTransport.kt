package moe.ouom.neriplayer.api.sync.github

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.api.sync.http.SyncResponseBodyReader
import moe.ouom.neriplayer.data.sync.unified.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GitHubIdentityVersion internal constructor(internal val target: String, internal val repositoryId: String,
    internal val head: GitHubSyncHead, internal val treeSha: String)
class IdentityGitHubException(val statusCode: Int) : IOException("Identity GitHub request failed ($statusCode)")

/** Stages one dedicated blob/tree/commit, then publishes with an exact beforeOid and force=false. */
class UnifiedIdentityGitHubTransport(
    private val owner: String,
    private val repository: String,
    token: String,
    client: OkHttpClient,
    apiBase: String = "https://api.github.com"
) : UnifiedIdentityRemoteTransport<GitHubIdentityVersion> {
    private val base = apiBase.trimEnd('/').toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) { "Unsafe identity GitHub endpoint" }
    }
    private val graphQl = GitHubArchiveRefUpdate.endpoint(base.toString())
    private val target = "${base}|${owner.lowercase()}/${repository.lowercase()}"
    private val authorization = "Bearer $token"
    private val http = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    init {
        require(Regex("[A-Za-z0-9_.-]{1,100}").matches(owner) && owner !in setOf(".", "..") &&
            Regex("[A-Za-z0-9_.-]{1,100}").matches(repository) && repository !in setOf(".", "..") && token.isNotBlank()) { "Invalid identity GitHub target" }
    }

    override suspend fun read(): RemoteIdentitySnapshot<GitHubIdentityVersion> = withContext(Dispatchers.Default) {
        val repositoryMetadata = getObject(endpoint())
        val branch = string(repositoryMetadata, "default_branch")
        require(branch.length <= 1024) { "Invalid identity branch" }
        val node = string(repositoryMetadata, "node_id")
        require(node.length <= 256) { "Invalid repository node" }
        val branchMetadata = getObject(endpoint("branches").newBuilder().addPathSegment(branch).build())
        require(string(branchMetadata, "name") == branch) { "Identity branch mismatch" }
        val commit = branchMetadata.getAsJsonObject("commit") ?: throw IOException("Identity branch has no commit")
        val head = objectId(commit)
        val tree = objectId(commit.getAsJsonObject("commit")?.getAsJsonObject("tree") ?: throw IOException("Identity branch has no tree"))
        val version = GitHubIdentityVersion(target, node, GitHubSyncHead(branch, head), tree)
        val url = endpoint("contents/$FILE_NAME").newBuilder().addQueryParameter("ref", head).build()
        val request = request(url).header("Accept", "application/vnd.github.raw+json").get().build()
        val call = http.newCall(request)
        val bytes = executeGitHubCall(call) { response ->
            when (response.code) {
                200 -> SyncResponseBodyReader.read(response.body, UnifiedIdentityCodec.MAX_BYTES, call::cancel)
                404 -> { call.cancel();null }
                else -> { call.cancel();throw IdentityGitHubException(response.code) }
            }
        }
        RemoteIdentitySnapshot(bytes, version)
    }

    override suspend fun compareAndSet(expected: GitHubIdentityVersion, bytes: ByteArray): Boolean = withContext(Dispatchers.Default) {
        require(expected.target == target) { "Identity version belongs to a different repository" }
        UnifiedIdentityCodec.decode(bytes)
        val blobBody = JsonObject().apply { addProperty("content", Base64.getEncoder().encodeToString(bytes));addProperty("encoding", "base64") }
        val blob = objectId(postObject(endpoint("git/blobs"), blobBody.toString()))
        val digest = MessageDigest.getInstance("SHA-1").apply {
            update("blob ${bytes.size}".toByteArray(Charsets.UTF_8));update(0.toByte());update(bytes)
        }.digest().joinToString("") { "%02x".format(it) }
        require(blob == digest) { "Identity blob acknowledgement mismatch" }
        val entry = JsonObject().apply { addProperty("path", FILE_NAME);addProperty("mode", "100644");addProperty("type", "blob");addProperty("sha", blob) }
        val treeBody = JsonObject().apply {
            addProperty("base_tree", expected.treeSha)
            add("tree", com.google.gson.JsonArray().apply { add(entry) })
        }
        val tree = objectId(postObject(endpoint("git/trees"), treeBody.toString()))
        val commitBody = JsonObject().apply {
            addProperty("message", "Sync UniMusic song identities");addProperty("tree", tree)
            add("parents", com.google.gson.JsonArray().apply { add(expected.head.sha) })
        }
        val commit = objectId(postObject(endpoint("git/commits"), commitBody.toString()))
        val body = GitHubArchiveRefUpdate.requestBody(expected.repositoryId, expected.head, commit)
        try {
            val response = postObject(graphQl, body, GitHubArchiveRefUpdate.MAX_RESPONSE_BYTES)
            GitHubArchiveRefUpdate.validateResponse(response.toString(), commit)
            true
        } catch (error: GitHubGraphQlException) {
            when {
                error.rateLimited -> throw IdentityGitHubException(429)
                error.staleReference -> false
                else -> throw IOException("Identity GitHub atomic publication was rejected")
            }
        }
    }

    private fun endpoint(path: String = ""): HttpUrl = base.newBuilder().apply {
        addPathSegment("repos");addPathSegment(owner);addPathSegment(repository)
        path.split('/').filter { it.isNotEmpty() }.forEach(::addPathSegment)
    }.build()

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", authorization)
        .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")

    private suspend fun getObject(url: HttpUrl): JsonObject = readObject(request(url).get().build())
    private suspend fun postObject(url: HttpUrl, body: String, maxBytes: Int = 1024 * 1024): JsonObject = readObject(request(url)
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build(), maxBytes)

    private suspend fun readObject(request: Request, maxBytes: Int = 1024 * 1024): JsonObject {
        val call = http.newCall(request)
        return executeGitHubCall(call) { response ->
            if (!response.isSuccessful) { call.cancel();throw IdentityGitHubException(response.code) }
            val bytes = SyncResponseBodyReader.read(response.body, maxBytes, call::cancel)
            try { JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject }
            catch (_: Exception) { throw IOException("Invalid identity GitHub response") }
        }
    }

    private fun string(value: JsonObject, key: String): String = value.get(key)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank()
    }?.asString ?: throw IOException("Missing identity GitHub response field")
    private fun objectId(value: JsonObject): String = string(value, "sha").also {
        require(it.matches(Regex("[0-9a-f]{40}"))) { "Invalid identity Git object" }
    }
    companion object { const val FILE_NAME = "unimusic-identities-v1.json" }
}
