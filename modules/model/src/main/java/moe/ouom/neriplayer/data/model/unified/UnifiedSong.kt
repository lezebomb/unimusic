package moe.ouom.neriplayer.data.model.unified

import java.util.UUID

/** A specific recording. Platform IDs belong to SongSourceKey, never to this ID. */
data class UnifiedSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val version: RecordingVersion = RecordingVersion.UNKNOWN,
    val isrc: String? = null,
    val musicBrainzRecordingId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long
) {
    init {
        require(UUID.fromString(id).toString() == id) { "Expected a canonical song UUID" }
        require(durationMs >= 0) { "Negative song duration" }
    }
}

enum class RecordingVersion {
    UNKNOWN, STUDIO, LIVE, COVER, INSTRUMENTAL, REMIX, EDIT, SPEED_ALTERED, REMASTER, RERECORDING
}

/** Composite key: delimiters in IDs cannot accidentally alias another source. */
data class SongSourceKey(
    val provider: String,
    val resourceId: String,
    val subResourceId: String = "",
    val recordingContext: String = ""
) {
    init {
        require(provider.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "Invalid provider ID" }
        require(resourceId.isNotBlank()) { "Missing source resource ID" }
    }
}

data class UnifiedSongMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val version: RecordingVersion = RecordingVersion.UNKNOWN,
    val isrc: String? = null,
    val musicBrainzRecordingId: String? = null
) {
    init { require(durationMs >= 0) { "Negative song duration" } }
}

/** Metadata import grants no new playback entitlement. */
enum class SourcePlaybackMode { METADATA_ONLY, EXTERNAL_PLAYER, AUTHORIZED_AUDIO }

enum class SourceAvailability {
    UNKNOWN, AVAILABLE, TEMPORARY_ERROR, AUTH_EXPIRED, REMOVED, REGION_RESTRICTED,
    MEMBERSHIP_REQUIRED, VERSION_MISMATCH, URL_EXPIRED
}

data class UnifiedSongSource(
    val id: String,
    val songId: String,
    val key: SongSourceKey,
    val deviceOnly: Boolean,
    val playbackMode: SourcePlaybackMode = SourcePlaybackMode.METADATA_ONLY,
    val availability: SourceAvailability = SourceAvailability.UNKNOWN,
    val checkedAt: Long? = null,
    val permissionExpiresAt: Long? = null
)
