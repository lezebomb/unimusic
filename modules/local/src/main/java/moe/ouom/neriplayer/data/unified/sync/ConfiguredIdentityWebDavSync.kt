package moe.ouom.neriplayer.data.unified.sync

import android.content.Context
import moe.ouom.neriplayer.data.unified.IdentityBackendSyncResult
import moe.ouom.neriplayer.data.unified.UnifiedIdentitySyncBackend
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.api.sync.webdav.UnifiedIdentityWebDavTransport
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.unified.GuardedIdentityRemoteTransport
import moe.ouom.neriplayer.data.sync.unified.IdentitySyncConfigurationChangedException
import okhttp3.OkHttpClient

class IdentityWebDavNotConfiguredException : IOException("Configure WebDAV before syncing identities")

/** Uses the existing encrypted configuration only for an explicit user invocation. */
class ConfiguredIdentityWebDavSync(context: Context, private val http: OkHttpClient) {
    private val app = context.applicationContext ?: context
    suspend fun synchronize(): IdentityBackendSyncResult = withContext(Dispatchers.IO) {
        val storage = WebDavStorage(app)
        if (!storage.isConfigured()) throw IdentityWebDavNotConfiguredException()
        val guard = storage.captureSyncMetadataGuard()
        val configuration = storage.snapshot()
        val isCurrent = { guard { } }
        if (!isCurrent()) throw IdentitySyncConfigurationChangedException()
        val url = WebDavApiClient.buildRemoteFileUrl(configuration.serverUrl, configuration.basePath)
        val transport = UnifiedIdentityWebDavTransport(url, configuration.username, configuration.password, http)
        val result = UnifiedIdentitySyncBackend.getInstance(app).synchronize(GuardedIdentityRemoteTransport(transport, isCurrent))
        if (!isCurrent()) throw IdentitySyncConfigurationChangedException()
        result
    }
}
