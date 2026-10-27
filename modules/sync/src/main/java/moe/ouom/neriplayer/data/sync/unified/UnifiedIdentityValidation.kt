package moe.ouom.neriplayer.data.sync.unified

import java.util.UUID
import moe.ouom.neriplayer.data.model.unified.RecordingVersion
import moe.ouom.neriplayer.data.model.unified.sync.*

/** Reject unsupported/private identities instead of silently truncating a sync dataset. */
object UnifiedIdentityValidation {
    const val MAX_IDENTITIES = 50_000
    const val MAX_REVISIONS = 50_000
    const val MAX_DEVICES = 64
    private val positiveId = Regex("[1-9][0-9]{0,19}")
    private val opaqueId = Regex("[A-Za-z0-9_-]{1,64}")
    private val biliId = Regex("BV[A-Za-z0-9]{10}")

    fun validate(data: UnifiedIdentityDataset) {
        require(data.schemaVersion == 1 && data.capability == "unimusic.unified-identities.v1") { "Unsupported unified identity protocol" }
        require(data.identities.size <= MAX_IDENTITIES && data.revisions.size <= MAX_REVISIONS) { "Unified identity dataset too large" }
        data.identities.forEach(::validateIdentity)
        val ids = data.identities.mapTo(hashSetOf()) { it.songId }
        val dots = mutableMapOf<UnifiedSyncDot, UnifiedSongRevision>()
        val devices = hashSetOf<String>()
        data.revisions.forEach { revision ->
            require(revision.songId in ids) { "Revision has no portable identity" }
            requireUuid(revision.dot.deviceId)
            require(revision.dot.counter > 0 && revision.dot.counter < Long.MAX_VALUE) { "Invalid causal counter" }
            require(revision.context.size <= MAX_DEVICES) { "Causal context too large" }
            revision.context.forEach { (device, counter) ->
                requireUuid(device)
                require(counter > 0 && counter < Long.MAX_VALUE) { "Invalid causal context" }
                devices.add(device)
            }
            devices.add(revision.dot.deviceId)
            require(devices.size <= MAX_DEVICES) { "Too many causal devices" }
            require((revision.context[revision.dot.deviceId] ?: 0L) < revision.dot.counter) { "Causal revision observes itself" }
            val previous = dots.putIfAbsent(revision.dot, revision)
            require(previous == null || previous == revision) { "Conflicting causal dot" }
            revision.metadata?.let(::validateMetadata)
        }
    }

    fun validateMetadata(metadata: PortableSongMetadata) {
        require(metadata.title.length <= 512 && metadata.artist.length <= 512 && metadata.album.length <= 512) { "Metadata too long" }
        require(metadata.durationMs in 0..7L * 86_400_000L) { "Invalid portable duration" }
        require(RecordingVersion.entries.any { it.name == metadata.recordingVersion }) { "Unknown recording version" }
    }

    fun validateIdentity(identity: PortableSongIdentity) {
        requireUuid(identity.songId)
        require(identity.recordingContext.isEmpty()) { "Unresolved recording context is device-local" }
        val valid = when (identity.provider) {
            "netease", "kuwo" -> positiveId.matches(identity.resourceId) && identity.subResourceId.isEmpty()
            "qqmusic" -> opaqueId.matches(identity.resourceId) && identity.subResourceId.isEmpty()
            "youtube_music" -> identity.resourceId.length >= 6 && opaqueId.matches(identity.resourceId) && identity.subResourceId.isEmpty()
            "bilibili" -> (biliId.matches(identity.resourceId) || positiveId.matches(identity.resourceId)) && positiveId.matches(identity.subResourceId)
            else -> false
        }
        require(valid) { "Unsupported or private source identity" }
    }

    private fun requireUuid(value: String) {
        require(UUID.fromString(value).toString() == value) { "Expected canonical UUID" }
    }
}
