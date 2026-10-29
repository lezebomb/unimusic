package moe.ouom.neriplayer.platform.bilibili.api.stream

import java.net.URI
import java.util.Locale

private val BILI_VIDEO_DOMAINS = listOf("bilivideo.com", "bilivideo.cn")

private fun isBiliVideoHost(host: String): Boolean = BILI_VIDEO_DOMAINS.any {
    host == it || host.endsWith(".$it")
}

fun isBiliStreamHost(host: String): Boolean {
    val normalized = host.trim().lowercase(Locale.ROOT).removeSuffix(".")
    return isBiliVideoHost(normalized) || normalized.endsWith(".mountaintoys.cn")
}

fun isBiliStreamUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme?.lowercase(Locale.ROOT) in setOf("https", "http") &&
        uri.rawUserInfo == null && isBiliStreamHost(uri.host.orEmpty())
}.getOrDefault(false)

private fun scoreBiliStreamUrl(url: String): Int {
    val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    return when {
        host.startsWith("upos-") && isBiliVideoHost(host) -> 3
        isBiliVideoHost(host) -> 2
        host.endsWith(".mountaintoys.cn") -> 1
        else -> 0
    }
}

fun prioritizeBiliStreamUrls(primaryUrl: String, backupUrls: List<String>): List<String> {
    val deduped = buildList {
        add(primaryUrl)
        addAll(backupUrls)
    }.map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    return deduped.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<String>> { scoreBiliStreamUrl(it.value) }
                .thenBy { it.index }
        )
        .map { it.value }
}
