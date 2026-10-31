package moe.ouom.neriplayer.core.player.resolver.unm

import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.settingsRepo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot

/**
 * Applies the persisted matching bridge configuration during cold start.
 */
internal fun PlayerManager.applyUnmBridgePreferences(preferences: PlaybackPreferenceSnapshot) {
    unmBridgeEnabled = preferences.unmBridgeEnabled
    unmBridgeBaseUrl = preferences.unmBridgeBaseUrl
}

/**
 * Keeps the in-memory matching bridge configuration in sync with the settings store.
 */
internal fun PlayerManager.observeUnmBridgeSettings() {
    ioScope.launch {
        settingsRepo.unmBridgeEnabledFlow.collect { enabled ->
            val previousEnabled = unmBridgeEnabled
            unmBridgeEnabled = enabled
            if (!previousEnabled && enabled && unmBridgeBaseUrl.isNotBlank()) {
                scheduleQualityRefresh(
                    source = PlaybackAudioSource.NETEASE,
                    reason = "unm_bridge_enabled"
                )
            }
        }
    }
    ioScope.launch {
        settingsRepo.unmBridgeBaseUrlFlow.collect { baseUrl ->
            val previousUrl = unmBridgeBaseUrl
            unmBridgeBaseUrl = baseUrl
            if (previousUrl != baseUrl && unmBridgeEnabled && baseUrl.isNotBlank()) {
                scheduleQualityRefresh(
                    source = PlaybackAudioSource.NETEASE,
                    reason = "unm_bridge_address_changed"
                )
            }
        }
    }
}
