package moe.ouom.neriplayer.data.unified

import java.util.UUID
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.*

/** Exact native identity only. URLs remain lazy and are never placed in a queue or saved library. */
fun catalogPlaybackSong(track: CatalogTrack): SongItem? {
    val source = track.source
    if (source.provider !in setOf("qqmusic", "kuwo") || !MusicSourceRegistry.hasAudioAdapter(source)) return null
    val metadata = track.metadata
    return SongItem(UUID.nameUUIDFromBytes("${source.provider}:${source.resourceId}".toByteArray(Charsets.UTF_8)).mostSignificantBits,
        metadata.title, metadata.artist, metadata.album, 0, metadata.durationMs, null,
        channelId = source.provider, audioId = source.resourceId)
}
