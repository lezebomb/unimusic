package moe.ouom.neriplayer.data.unified

import java.security.MessageDigest
import java.util.UUID
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.*
import moe.ouom.neriplayer.data.sync.identity.normalizedSubAudioId
import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId

fun sourceCandidateForSong(song: SongItem): RecordingSourceCandidate? {
    val key = if (LocalSongSupport.isLocalSong(song)) {
        val reference = LocalSongSupport.identityMediaReference(song) ?: return null
        SongSourceKey("local", UUID.nameUUIDFromBytes("${song.stableKey()}\u0000$reference".toByteArray()).toString())
    } else {
        if (!song.streamUrl.isNullOrBlank()) return null
        when (song.identity().album) {
            "netease" -> SongSourceKey("netease", (song.audioId ?: song.id.toString()).takeIf(::positiveSourceId) ?: return null)
            "bilibili" -> SongSourceKey("bilibili",
                (song.audioId ?: song.id.toString()).takeIf(::positiveSourceId) ?: return null,
                normalizedSubAudioId("bilibili", song.subAudioId, song.album)?.takeIf(::positiveSourceId) ?: return null)
            "youtube_music" -> SongSourceKey("youtube_music",
                (extractYouTubeMusicVideoId(song.mediaUri) ?: song.audioId)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return null)
            else -> return null
        }
    }
    return RecordingSourceCandidate(key, sourceMatchMetadata(song))
}

internal fun sourceMatchMetadata(song: SongItem) = UnifiedSongMetadata(
    song.originalName ?: song.name, song.originalArtist ?: song.artist, song.album, song.durationMs.coerceAtLeast(0))

internal fun UnifiedSongMetadata.recording() = RecordingMetadata(title, artist, durationMs, version, album)

internal fun sourceMatchFingerprint(metadata: UnifiedSongMetadata): String {
    val text = listOf(metadata.title, metadata.artist, metadata.album, metadata.durationMs.toString(), metadata.version.name)
        .joinToString("|") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

internal fun positiveSourceId(value: String): Boolean = value.toLongOrNull()?.takeIf { it > 0 }?.toString() == value

internal fun validMatchKey(key: SongSourceKey): Boolean = key.recordingContext.isEmpty() && when (key.provider) {
    "netease", "kuwo" -> positiveSourceId(key.resourceId) && key.subResourceId.isEmpty()
    "bilibili" -> positiveSourceId(key.resourceId) && positiveSourceId(key.subResourceId)
    "youtube_music" -> key.resourceId.matches(Regex("[A-Za-z0-9_-]{11}")) && key.subResourceId.isEmpty()
    "local" -> runCatching { UUID.fromString(key.resourceId).toString() == key.resourceId }.getOrDefault(false) && key.subResourceId.isEmpty()
    else -> false
}

internal fun nativeMatchKey(key: SongSourceKey) = validMatchKey(key) && key.provider != "kuwo"
