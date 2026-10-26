package moe.ouom.neriplayer.ui.viewmodel.tab

import java.net.URI
import moe.ouom.neriplayer.common.search.SearchTextMatcher
import moe.ouom.neriplayer.data.model.unified.CatalogTrack
import moe.ouom.neriplayer.data.model.unified.SourcePlaybackMode

internal fun catalogSearchResultKey(track: CatalogTrack): String = with(track.source) {
    // Length-prefix all components; source IDs may themselves contain separators.
    listOf("catalog", provider, resourceId, subResourceId, recordingContext)
        .joinToString("|") { "${it.length}:$it" }
}

internal fun catalogSearchValues(track: CatalogTrack): List<Any?> = with(track.metadata) {
    listOf(SearchTextMatcher.value(title, 0), SearchTextMatcher.value(artist, 24),
        SearchTextMatcher.value(album, 40), SearchTextMatcher.value(track.source.resourceId, 84))
}

/** A catalog response cannot request arbitrary navigation, or acquire playback permission. */
internal fun catalogExternalPageUrl(track: CatalogTrack): String? {
    if (track.source.provider != "kuwo" || track.playbackMode != SourcePlaybackMode.METADATA_ONLY) return null
    val id = track.source.resourceId
    if (id.toLongOrNull()?.takeIf { it > 0 }?.toString() != id) return null
    val expectedPath = "/play_detail/$id"
    val uri = try { URI(track.externalPageUrl ?: return null) } catch (_: Exception) { return null }
    if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals("www.kuwo.cn", ignoreCase = true) ||
        uri.port !in listOf(-1, 443) || uri.rawUserInfo != null || uri.rawPath != expectedPath ||
        uri.rawQuery != null || uri.rawFragment != null) return null
    return "https://www.kuwo.cn$expectedPath"
}
