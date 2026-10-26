package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import java.security.MessageDigest
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.platform.youtube.api.transport.buildBootstrapAuthFingerprint

/** In-memory invalidation keys only; never persisted with candidates or written to logs. */
internal data class SourceChoiceSession(
    val bilibili: String,
    val netease: String,
    val youtube: String,
    val youtubeEnabled: Boolean
)

internal fun sourceChoiceSession(
    biliCookies: Map<String, String>,
    neteaseCookies: Map<String, String>,
    youtubeAuth: YouTubeAuthBundle,
    youtubeEnabled: Boolean
): SourceChoiceSession = SourceChoiceSession(
    bilibili = sourceChoiceFingerprint(listOf("DedeUserID", "SESSDATA", "bili_jct").map { biliCookies[it].orEmpty() }),
    // Match the native client's public-search session identity, excluding routine cookie churn.
    netease = sourceChoiceFingerprint(neteaseCookies.filterKeys {
        it !in setOf("NMTID", "__csrf", "_ntes_nuid", "__remember_me", "os", "appver")
    }.toSortedMap().flatMap { (key, value) -> listOf(key, value) }),
    youtube = sourceChoiceFingerprint(listOf(youtubeAuth.buildBootstrapAuthFingerprint(), youtubeAuth.authorization)),
    youtubeEnabled = youtubeEnabled
)

private fun sourceChoiceFingerprint(parts: List<String>): String {
    val text = parts.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
