package moe.ouom.neriplayer.ui.viewmodel.tab

import java.net.URI
import moe.ouom.neriplayer.common.search.SearchTextMatcher
import moe.ouom.neriplayer.data.model.unified.CatalogTrack

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
    val expected = moe.ouom.neriplayer.data.model.unified.MusicSourceRegistry.publicPageUrl(track.source) ?: return null
    val target = URI(expected)
    val uri = try { URI(track.externalPageUrl ?: return null) } catch (_: Exception) { return null }
    if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals(target.host, ignoreCase = true) ||
        uri.port !in listOf(-1, 443) || uri.rawUserInfo != null || uri.rawPath != target.rawPath ||
        uri.rawQuery != null || uri.rawFragment != null) return null
    return expected
}
