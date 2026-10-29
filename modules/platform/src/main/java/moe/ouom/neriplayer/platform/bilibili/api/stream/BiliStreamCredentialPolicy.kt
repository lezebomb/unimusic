package moe.ouom.neriplayer.platform.bilibili.api.stream

import java.net.URI
import java.util.Locale

private const val BILI_STREAM_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private val BILI_PRIVATE_HEADERS = listOf("cookie", "cookie2", "authorization", "referer", "user-agent")

/** Attach saved credentials only to a validated encrypted stream destination, never its logical song's provider. */
fun buildBiliStreamRequestHeaders(streamUrl: String, original: Map<String, String>, cookieHeader: String): Map<String, String> {
    if (!isBiliStreamUrl(streamUrl)) return original
    return original.filterKeys {
        it.lowercase(Locale.ROOT) !in BILI_PRIVATE_HEADERS
    }.toMutableMap().apply {
        put("Referer", "https://www.bilibili.com")
        put("User-Agent", BILI_STREAM_USER_AGENT)
        if (URI(streamUrl).scheme.equals("https", ignoreCase = true) && cookieHeader.isNotBlank()) {
            put("Cookie", cookieHeader)
        }
    }
}
