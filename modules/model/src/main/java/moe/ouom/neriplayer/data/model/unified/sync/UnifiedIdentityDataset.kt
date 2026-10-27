package moe.ouom.neriplayer.data.model.unified.sync

import kotlinx.serialization.Serializable

/** Independent portable identity protocol. Never attach this to legacy SyncData. */
@Serializable
data class UnifiedIdentityDataset(
    val schemaVersion: Int = 1,
    val capability: String = "unimusic.unified-identities.v1",
    val identities: List<PortableSongIdentity> = emptyList(),
    val revisions: List<UnifiedSongRevision> = emptyList()
)

/** Only exact public source IDs; no availability, account, device URI or audio address. */
@Serializable
data class PortableSongIdentity(
    val songId: String,
    val provider: String,
    val resourceId: String,
    val subResourceId: String = "",
    val recordingContext: String = ""
)

@Serializable
data class PortableSongMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val recordingVersion: String = "UNKNOWN"
)

/** Allocated from a persisted per-device monotonic counter, never from wall-clock time. */
@Serializable
data class UnifiedSyncDot(val deviceId: String, val counter: Long)

/**
 * Context contains only observed revisions of this identity/its known aliases, not unrelated songs.
 * Null metadata is a tombstone. A put after observing a tombstone is an explicit restore.
 */
@Serializable
data class UnifiedSongRevision(
    val songId: String,
    val dot: UnifiedSyncDot,
    val context: Map<String, Long> = emptyMap(),
    val metadata: PortableSongMetadata?
)
