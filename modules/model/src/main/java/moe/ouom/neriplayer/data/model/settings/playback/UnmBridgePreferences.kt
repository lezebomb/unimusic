package moe.ouom.neriplayer.data.model.settings.playback

/** Off by default: the bridge is a self-hosted endpoint the user must opt into. */
const val DEFAULT_UNM_BRIDGE_ENABLED = false

/** Blank means "not configured"; a blank address can never activate the bridge. */
const val DEFAULT_UNM_BRIDGE_BASE_URL = ""

private val unmBridgeHostOnlyRegex = Regex("^https?:///*$", RegexOption.IGNORE_CASE)
private val unmBridgeTrailingSlashRegex = Regex("/+$")

/**
 * Normalizes user input for the bridge base URL.
 *
 * Only trimming and trailing-slash removal happen here; scheme/host validation stays with the
 * platform client so a malformed entry is reported to the user instead of silently rewritten.
 */
fun normalizeUnmBridgeBaseUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (unmBridgeHostOnlyRegex.matches(trimmed)) return ""
    return trimmed.replace(unmBridgeTrailingSlashRegex, "")
}
