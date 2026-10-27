package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import java.util.concurrent.TimeUnit
import moe.ouom.neriplayer.api.sync.http.SyncResponseBodyReader
import moe.ouom.neriplayer.data.model.sync.transport.WebDavArchiveObjectPolicy
import moe.ouom.neriplayer.data.sync.unified.*
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Null ETag means an observed missing file; creation still requires If-None-Match. */
data class WebDavIdentityVersion(val etag: String?)
class UnifiedIdentityWebDavException(val statusCode: Int) : IOException("Identity WebDAV request failed ($statusCode)")

/** Dedicated sibling document; it cannot replace the legacy sync file or follow redirects. */
class UnifiedIdentityWebDavTransport(
    legacyRemoteUrl: String,
    username: String,
    password: String,
    client: OkHttpClient
) : UnifiedIdentityRemoteTransport<WebDavIdentityVersion> {
    private val url = WebDavApiClient.buildSiblingFileUrl(legacyRemoteUrl, FILE_NAME).toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) {
            "Identity sync endpoint must not embed credentials, query or fragment"
        }
    }
    private val authorization = Credentials.basic(username, password)
    private val http = client.newBuilder().callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    override suspend fun read(): RemoteIdentitySnapshot<WebDavIdentityVersion> {
        val request = Request.Builder().url(url).header("Authorization", authorization)
            .header("Accept-Encoding", "identity").get().build()
        return executeWebDavArchiveRead(http.newCall(request)) { response, cancel ->
            when (response.code) {
                200 -> {
                    val etag = response.header("ETag")?.trim()
                    if (etag == null || !validEtag(etag)) {
                        cancel()
                        throw IOException("Identity WebDAV server requires a strong ETag")
                    }
                    val bytes = SyncResponseBodyReader.read(response.body, UnifiedIdentityCodec.MAX_BYTES, cancel)
                    RemoteIdentitySnapshot(bytes, WebDavIdentityVersion(etag))
                }
                404 -> { cancel();RemoteIdentitySnapshot(null, WebDavIdentityVersion(null)) }
                else -> { cancel();throw UnifiedIdentityWebDavException(response.code) }
            }
        }
    }

    override suspend fun compareAndSet(expected: WebDavIdentityVersion, bytes: ByteArray): Boolean {
        UnifiedIdentityCodec.decode(bytes) // Only the standalone portable protocol can enter this document.
        val request = Request.Builder().url(url).header("Authorization", authorization)
        if (expected.etag == null) request.header("If-None-Match", "*")
        else {
            require(validEtag(expected.etag)) { "Invalid identity WebDAV version" }
            request.header("If-Match", expected.etag)
        }
        request.put(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return executeWebDavArchiveRead(http.newCall(request.build())) { response, cancel ->
            cancel() // No response body is needed, including error bodies that may contain private server text.
            when {
                response.code in 200..299 -> true
                response.code == 412 -> false
                else -> throw UnifiedIdentityWebDavException(response.code)
            }
        }
    }

    private fun validEtag(etag: String): Boolean = etag.length <= 256 && WebDavArchiveObjectPolicy.isStrongETag(etag)
    companion object { const val FILE_NAME = "unimusic-identities-v1.json" }
}
