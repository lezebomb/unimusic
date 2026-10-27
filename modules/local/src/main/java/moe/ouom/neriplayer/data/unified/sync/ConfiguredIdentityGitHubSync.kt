package moe.ouom.neriplayer.data.unified.sync

import android.content.Context
import moe.ouom.neriplayer.data.unified.IdentityBackendSyncResult
import moe.ouom.neriplayer.data.unified.UnifiedIdentitySyncBackend
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.api.sync.github.UnifiedIdentityGitHubTransport
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.unified.GuardedIdentityRemoteTransport
import moe.ouom.neriplayer.data.sync.unified.IdentitySyncConfigurationChangedException
import okhttp3.OkHttpClient

class IdentityGitHubNotConfiguredException : IOException("Configure GitHub before syncing identities")

class ConfiguredIdentityGitHubSync(context: Context, private val http: OkHttpClient) {
    private val app = context.applicationContext ?: context
    suspend fun synchronize(): IdentityBackendSyncResult = withContext(Dispatchers.IO) {
        val storage = SecureTokenStorage(app)
        if (!storage.isConfigured()) throw IdentityGitHubNotConfiguredException()
        val guard = storage.captureSyncMetadataGuard()
        val configuration = storage.snapshot()
        val isCurrent = { guard { } }
        if (!isCurrent()) throw IdentitySyncConfigurationChangedException()
        val transport = UnifiedIdentityGitHubTransport(configuration.repoOwner, configuration.repoName, configuration.token, http)
        val result = UnifiedIdentitySyncBackend.getInstance(app).synchronize(GuardedIdentityRemoteTransport(transport, isCurrent))
        if (!isCurrent()) throw IdentitySyncConfigurationChangedException()
        result
    }
}
