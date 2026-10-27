package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.activity.auth.QQMusicWebLoginActivity
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicCredential
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicLoginStatus
import moe.ouom.neriplayer.platform.qqmusic.auth.QQMusicAuthRepository

internal data class QQMusicSettingsActions(val login: () -> Unit, val manage: () -> Unit)

/** Shared by onboarding and settings, so neither surface retains the old QQ placeholder. */
@Composable
internal fun rememberQQMusicAccountEntry(): Pair<SettingsAccountCardUiState, QQMusicSettingsActions> {
    val repository by produceState<QQMusicAuthRepository?>(null) {
        value = withContext(Dispatchers.IO) { AppContainer.qqMusicAuthRepo }
    }
    val ready = repository ?: return SettingsAccountCardUiState(SettingsAccountPlatform.QqMusic,
        loginSupported = false, statusText = stringResource(CoreCommonR.string.settings_account_profile_loading)) to
        QQMusicSettingsActions({}, {})
    val account by ready.state.collectAsStateWithLifecycle()
    val actions = rememberQQMusicSettingsActions(ready)
    return SettingsAccountCardUiState(platform = SettingsAccountPlatform.QqMusic, loginSupported = true,
        hasSavedAuthorization = account.accountId != null,
        authorizationComplete = account.status == QQMusicLoginStatus.VERIFIED,
        profile = account.accountId?.let { SettingsAccountProfile(account.nickname ?: it, null) },
        statusText = stringResource(qqMusicStatusText(account.status))) to actions
}

@Composable
internal fun rememberQQMusicSettingsActions(repository: QQMusicAuthRepository): QQMusicSettingsActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by repository.state.collectAsStateWithLifecycle()
    var show by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var launchedRevision by rememberSaveable { mutableLongStateOf(-1) }
    var loginPending by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        loginPending = false
        if (result.resultCode == Activity.RESULT_OK) {
            val credential = result.data?.getStringExtra(QQMusicWebLoginActivity.RESULT_AUTH_JSON)
                ?.let(QQMusicCredential::fromStorageJson)
            if (credential != null) {
                show = true
                busy = true
                scope.launch {
                    try {
                        if (repository.save(credential, launchedRevision)) repository.check()
                        else message = context.getString(CoreCommonR.string.qq_music_auth_changed)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = context.getString(CoreCommonR.string.qq_music_auth_storage_error) }
                    finally { busy = false }
                }
            }
        }
    }
    val login: () -> Unit = {
        if (!busy && !loginPending) {
            show = false
            message = null
            launchedRevision = repository.generation()
            loginPending = true
            launcher.launch(Intent(context, QQMusicWebLoginActivity::class.java))
        }
    }
    if (show) {
        AlertDialog(onDismissRequest = { if (!busy) show = false },
            title = { Text(stringResource(CoreCommonR.string.settings_qq_music)) },
            text = { Column {
                if (busy) Text(stringResource(CoreCommonR.string.qq_music_auth_checking))
                Text(stringResource(qqMusicStatusText(account.status)))
                Text(stringResource(CoreCommonR.string.qq_music_auth_scope))
                if (!account.persistent) Text(stringResource(CoreCommonR.string.qq_music_auth_volatile))
                message?.let { Text(it) }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { try { repository.check() } finally { busy = false } }
            }) { Text(stringResource(CoreCommonR.string.qq_music_check_login)) } },
            dismissButton = { Column {
                TextButton(enabled = !busy, onClick = login) { Text(stringResource(CoreCommonR.string.qq_music_relogin)) }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try { repository.clear(); show = false }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message = context.getString(CoreCommonR.string.qq_music_auth_storage_error) }
                        finally { busy = false }
                    }
                }) { Text(stringResource(CoreCommonR.string.qq_music_logout)) }
            } })
    }
    return QQMusicSettingsActions(login) { show = true }
}

internal fun qqMusicStatusText(status: QQMusicLoginStatus): Int = when (status) {
    QQMusicLoginStatus.MISSING -> CoreCommonR.string.qq_music_auth_missing
    QQMusicLoginStatus.SAVED_UNVERIFIED -> CoreCommonR.string.qq_music_auth_saved
    QQMusicLoginStatus.VERIFIED -> CoreCommonR.string.qq_music_auth_verified
    QQMusicLoginStatus.EXPIRED -> CoreCommonR.string.qq_music_auth_expired
    QQMusicLoginStatus.UNAVAILABLE -> CoreCommonR.string.qq_music_auth_unavailable
    QQMusicLoginStatus.CONTEXT_CHANGED -> CoreCommonR.string.qq_music_auth_changed
}
