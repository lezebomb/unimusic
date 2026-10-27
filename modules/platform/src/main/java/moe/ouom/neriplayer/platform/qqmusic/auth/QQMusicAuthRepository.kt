@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.platform.qqmusic.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.storage.SecurePreferencesOpener
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicAccountCheck
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicAccountClient
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicCredential
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicLoginStatus

data class QQMusicAccountState(val accountId: String? = null, val status: QQMusicLoginStatus = QQMusicLoginStatus.MISSING,
    val nickname: String? = null, val savedAt: Long = 0, val persistent: Boolean = true)

class QQMusicAuthRepository(context: Context, private val client: QQMusicAccountClient = QQMusicAccountClient(),
    private val preferences: SharedPreferences = openQQMusicPreferences(context)) {
    private val lock = Any()
    private var revision = runCatching { preferences.getLong("revision", 0) }.getOrDefault(0)
    private var credential = runCatching { preferences.getString("credential", null)?.let(QQMusicCredential::fromStorageJson) }.getOrNull()
    private val _state = MutableStateFlow(snapshot())
    val state = _state.asStateFlow()
    fun generation(): Long = synchronized(lock) { revision }

    /** Keep credentials inside the account/protocol layer. A late result cannot survive logout. */
    suspend fun resolveAudio(mid: String, audio: moe.ouom.neriplayer.platform.qqmusic.api.QQMusicAudioClient):
        moe.ouom.neriplayer.platform.search.api.client.CatalogAudioResolution {
        val captured = synchronized(lock) { credential to revision }
        val result = audio.resolve(mid, captured.first)
        return synchronized(lock) {
            if (revision == captured.second && credential === captured.first) result
            else moe.ouom.neriplayer.platform.search.api.client.CatalogAudioResolution(
                moe.ouom.neriplayer.data.model.unified.SourceCheckStatus.CONTEXT_CHANGED)
        }
    }

    private fun snapshot() = QQMusicAccountState(credential?.accountId,
        if (credential == null) QQMusicLoginStatus.MISSING else QQMusicLoginStatus.SAVED_UNVERIFIED,
        savedAt = runCatching { preferences.getLong("saved_at", 0) }.getOrDefault(0),
        persistent = SecurePreferencesOpener.isPersistent(preferences))

    suspend fun save(value: QQMusicCredential, expectedRevision: Long): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (revision != expectedRevision) return@synchronized false
            val nextRevision = Math.addExact(revision, 1)
            if (!preferences.edit().putString("credential", value.toStorageJson())
                    .putLong("saved_at", System.currentTimeMillis()).putLong("revision", nextRevision).commit())
                throw IOException("QQ account could not be saved")
            credential = value
            revision = nextRevision
            _state.value = snapshot()
            true
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val nextRevision = Math.addExact(revision, 1)
            if (!preferences.edit().clear().putLong("revision", nextRevision).commit()) throw IOException("QQ account could not be cleared")
            credential = null
            revision = nextRevision
            _state.value = snapshot()
        }
    }

    suspend fun check(): QQMusicAccountCheck {
        val captured = synchronized(lock) { credential?.let { it to revision } }
            ?: return QQMusicAccountCheck(QQMusicLoginStatus.MISSING)
        val result = client.check(captured.first)
        return synchronized(lock) {
            if (revision == captured.second && credential === captured.first) {
                _state.value = snapshot().copy(status = result.status, nickname = result.nickname)
                result
            } else QQMusicAccountCheck(QQMusicLoginStatus.CONTEXT_CHANGED)
        }
    }
}

private fun openQQMusicPreferences(context: Context): SharedPreferences {
    val app = context.applicationContext
    val name = "qq_music_auth_secure_prefs"
    return SecurePreferencesOpener.open(name, create = {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(app, name, key, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }, delete = { app.deleteSharedPreferences(name) })
}
