package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.platform.youtube.api.transport.buildBootstrapAuthFingerprint
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate

/** Ephemeral guards only. Never persisted or logged; no cookie/token is copied into match rows. */
internal fun sourceResolutionSession(provider: String): Any {
    val repositories = PlayerDependencies.repositories
    return when (provider) {
        "qqmusic" -> repositories.qqMusicAuthRepo.generation()
        "netease", "gdstudio_netease" -> repositories.neteaseClient.searchSessionIdentity()
        "bilibili" -> repositories.biliCookieRepo.cookieFlow.value.let { cookies ->
            listOf(cookies["DedeUserID"], cookies["SESSDATA"], cookies["bili_jct"])
        }
        "youtube_music" -> repositories.youtubeAuthRepo.authFlow.value.let { auth ->
            Triple(auth.buildBootstrapAuthFingerprint(), auth.authorization, YouTubeFeatureGate.isEnabled())
        }
        else -> Unit
    }
}
