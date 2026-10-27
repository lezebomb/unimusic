package moe.ouom.neriplayer.platform.qqmusic.api.auth

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun isTrustedQQMusicLoginUrl(raw: String): Boolean {
    if (raw == "about:blank") return true
    val url = raw.toHttpUrlOrNull() ?: return false
    return url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        setOf("qq.com", "gtimg.com", "qpic.cn").any { url.host == it || url.host.endsWith(".$it") }
}
