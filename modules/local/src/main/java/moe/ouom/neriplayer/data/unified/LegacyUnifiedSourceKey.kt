package moe.ouom.neriplayer.data.unified

import java.util.Locale
import java.util.UUID
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity
import moe.ouom.neriplayer.data.model.unified.SongSourceKey

/** Only exact provider IDs are reused. Unresolved legacy identities stay separate. */
internal fun legacyUnifiedSourceKey(track: TrackEntity): SongSourceKey {
    val provider = when (val channel = track.channelId?.trim()?.lowercase(Locale.ROOT)) {
        "youtube", "ytmusic", "youtubemusic", "youtube_music" -> "youtube_music"
        "netease", "bilibili", "qqmusic", "kuwo" -> channel
        else -> "legacy"
    }
    val resource = track.audioId?.takeIf { it.isNotBlank() }
        ?: track.songId.takeIf { provider == "netease" && it > 0 }?.toString()
    if (provider == "legacy" || resource == null) {
        // Do not copy private paths or signed URIs into portable source IDs.
        return SongSourceKey("legacy", legacyIdentityDigest(track.identityKey))
    }
    val subResource = if (provider == "bilibili") track.subAudioId.orEmpty() else ""
    val unresolvedContext = if (provider == "bilibili" && subResource.isBlank()) {
        "unresolved:${legacyIdentityDigest(track.identityKey)}"
    } else ""
    return SongSourceKey(provider, resource, subResource, unresolvedContext)
}

private fun legacyIdentityDigest(key: String): String =
    UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
