package moe.ouom.neriplayer.platform.search.api.client

import moe.ouom.neriplayer.data.model.unified.SourceCheckStatus
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Short-lived response only. Signed URLs and account credentials must never enter match storage. */
class CatalogAudioResolution(val status: SourceCheckStatus, val metadata: UnifiedSongMetadata? = null,
    val url: String? = null) {
    override fun toString() = "CatalogAudioResolution(status=$status)"
}

/** Search endpoints sometimes HTML-escape metadata that detail endpoints return literally. */
internal fun decodeCatalogMetadataText(value: String): String = value
    .replace(Regex("\\\\+u0026", RegexOption.IGNORE_CASE), "&")
    .replace("&nbsp;", " ", ignoreCase = true).replace("&amp;", "&", ignoreCase = true)
    .replace("&quot;", "\"", ignoreCase = true).replace("&apos;", "'", ignoreCase = true)
    .replace("&#39;", "'").trim().replace(Regex("\\s+"), " ")

internal fun catalogMediaUrl(raw: String, domains: Set<String>): String? {
    val url = raw.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.fragment != null || domains.none { url.host == it || url.host.endsWith(".$it") } ||
        !url.encodedPath.endsWith(".mp3", ignoreCase = true)) return null
    return url.toString()
}
