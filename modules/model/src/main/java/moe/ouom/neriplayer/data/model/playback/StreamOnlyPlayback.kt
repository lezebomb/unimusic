package moe.ouom.neriplayer.data.model.playback

/** Internal fragment, never sent in an HTTP request; survives cache-key/MediaItem replacement. */
private const val STREAM_ONLY_FRAGMENT = "unimusic-stream-only"

fun streamOnlyPlaybackUrl(url: String): String = url.substringBefore('#') + "#" + STREAM_ONLY_FRAGMENT

fun isStreamOnlyPlaybackUrl(url: String?): Boolean =
    url?.substringAfter('#', "")?.split('&')?.contains(STREAM_ONLY_FRAGMENT) == true
