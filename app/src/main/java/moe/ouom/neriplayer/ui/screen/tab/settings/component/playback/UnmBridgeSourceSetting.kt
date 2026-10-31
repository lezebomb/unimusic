package moe.ouom.neriplayer.ui.screen.tab.settings.component.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.playback.normalizeUnmBridgeBaseUrl
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.platform.unm.api.UnmBridgeClient
import moe.ouom.neriplayer.ui.screen.tab.settings.component.AutoSettingSpecListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField

private val httpAddressRegex = Regex("^https?://[^\\s/]+", RegexOption.IGNORE_CASE)

/**
 * Opt-in row for the self-hosted full-recording matching service.
 *
 * The switch ships off and the address ships blank, so nothing here can silently reach a network
 * service the user never entered.
 */
@Composable
internal fun UnmBridgeSourceSetting(
    repository: SettingsRepository,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val enabled by repository.unmBridgeEnabledFlow.collectAsState(initial = false)
    val baseUrl by repository.unmBridgeBaseUrlFlow.collectAsState(initial = "")
    var showDialog by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var checkPassed by remember { mutableStateOf<Boolean?>(null) }

    AutoSettingSpecListItem(
        setting = AutoSettingsSchema.playback.unmBridgeEnabled,
        supportingContent = {
            Text(stringResource(CoreCommonR.string.settings_unm_bridge_enabled_desc))
        },
        trailingContent = {
            MiuixSettingsSwitch(
                checked = enabled,
                onCheckedChange = { value ->
                    scope.launch { repository.setUnmBridgeEnabled(value) }
                }
            )
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = {
            scope.launch { repository.setUnmBridgeEnabled(!enabled) }
        }
    )

    AutoSettingSpecListItem(
        setting = AutoSettingsSchema.playback.unmBridgeBaseUrl,
        enabled = enabled,
        supportingContent = {
            Text(
                baseUrl.ifBlank {
                    stringResource(CoreCommonR.string.settings_unm_bridge_base_url_unset)
                }
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        onClick = {
            draft = baseUrl
            checkPassed = null
            showDialog = true
        }
    )

    if (!showDialog) return

    MiuixSettingsDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(stringResource(CoreCommonR.string.settings_unm_bridge_dialog_title)) },
        text = {
            Column {
                MiuixSettingsTextField(
                    value = draft,
                    onValueChange = { value ->
                        draft = value
                        checkPassed = null
                    },
                    placeholder = {
                        Text(stringResource(CoreCommonR.string.settings_unm_bridge_dialog_hint))
                    },
                    singleLine = true
                )
                UnmBridgeDialogStatus(
                    invalid = normalizeUnmBridgeBaseUrl(draft).let { it.isNotEmpty() && !httpAddressRegex.matches(it) },
                    checking = checking,
                    checkPassed = checkPassed
                )
            }
        },
        confirmButton = {
            Row(modifier = Modifier.padding(end = 8.dp)) {
                MiuixSettingsTextButton(
                    enabled = !checking,
                    onClick = {
                        val candidate = normalizeUnmBridgeBaseUrl(draft)
                        if (!httpAddressRegex.matches(candidate)) {
                            checkPassed = false
                            return@MiuixSettingsTextButton
                        }
                        checking = true
                        scope.launch {
                            val version = UnmBridgeClient().probe(candidate)
                            checking = false
                            checkPassed = version != null
                        }
                    },
                    text = {
                        Text(
                            stringResource(
                                if (checking) CoreCommonR.string.settings_unm_bridge_checking
                                else CoreCommonR.string.settings_unm_bridge_check
                            )
                        )
                    }
                )
                val saved = normalizeUnmBridgeBaseUrl(draft)
                MiuixSettingsTextButton(
                    enabled = !checking && saved.isNotEmpty() && httpAddressRegex.matches(saved),
                    onClick = {
                        scope.launch {
                            repository.setUnmBridgeBaseUrl(saved)
                            if (!enabled) repository.setUnmBridgeEnabled(true)
                        }
                        showDialog = false
                    },
                    text = { Text(stringResource(CoreCommonR.string.action_done)) }
                )
            }
        }
    )
}

@Composable
private fun UnmBridgeDialogStatus(invalid: Boolean, checking: Boolean, checkPassed: Boolean?) {
    if (invalid) {
        Text(
            text = stringResource(CoreCommonR.string.settings_unm_bridge_invalid_address),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    if (checking) {
        Text(
            text = stringResource(CoreCommonR.string.settings_unm_bridge_checking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    when (checkPassed) {
        true -> Text(
            text = stringResource(CoreCommonR.string.settings_unm_bridge_check_ok),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        false -> Text(
            text = stringResource(CoreCommonR.string.settings_unm_bridge_check_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        null -> Unit
    }
}
