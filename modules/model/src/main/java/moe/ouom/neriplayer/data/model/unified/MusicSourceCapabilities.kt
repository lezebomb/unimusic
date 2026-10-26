package moe.ouom.neriplayer.data.model.unified

import java.util.UUID

enum class SourceAudioSupport { LOCAL_FILE, PLATFORM_API, METADATA_ONLY, UNKNOWN }

/** Describes implemented adapters, never a user's entitlement or a promise of playable audio. */
data class MusicSourceCapabilities(val metadataSearch: Boolean, val audio: SourceAudioSupport)

object MusicSourceRegistry {
    private val providers = mapOf(
        "local" to MusicSourceCapabilities(true, SourceAudioSupport.LOCAL_FILE),
        "netease" to MusicSourceCapabilities(true, SourceAudioSupport.PLATFORM_API),
        "bilibili" to MusicSourceCapabilities(true, SourceAudioSupport.PLATFORM_API),
        "youtube_music" to MusicSourceCapabilities(true, SourceAudioSupport.PLATFORM_API),
        "kuwo" to MusicSourceCapabilities(true, SourceAudioSupport.METADATA_ONLY)
    )
    private val unknown = MusicSourceCapabilities(false, SourceAudioSupport.UNKNOWN)

    fun capabilities(provider: String) = providers[provider] ?: unknown

    fun validKey(key: SongSourceKey): Boolean = key.recordingContext.isEmpty() && when (key.provider) {
        "netease", "kuwo" -> positiveId(key.resourceId) && key.subResourceId.isEmpty()
        "bilibili" -> positiveId(key.resourceId) && positiveId(key.subResourceId)
        "youtube_music" -> key.resourceId.matches(Regex("[A-Za-z0-9_-]{11}")) && key.subResourceId.isEmpty()
        "local" -> runCatching { UUID.fromString(key.resourceId).toString() == key.resourceId }.getOrDefault(false) && key.subResourceId.isEmpty()
        else -> false
    }

    fun canResolveAudio(key: SongSourceKey): Boolean = validKey(key) &&
        capabilities(key.provider).audio in setOf(SourceAudioSupport.LOCAL_FILE, SourceAudioSupport.PLATFORM_API)

    private fun positiveId(value: String) = value.toLongOrNull()?.takeIf { it > 0 }?.toString() == value
}

/** Device-only observation. ADDRESS_RESOLVED means an API returned a URL, not that playback ran. */
enum class SourceCheckStatus {
    ADDRESS_RESOLVED, LOCAL_READABLE, METADATA_ONLY, UNSUPPORTED, DISABLED, OFFLINE,
    USER_REJECTED, METADATA_MISMATCH, AUTH_REQUIRED, NO_PERMISSION, PREVIEW_ONLY,
    EXTERNAL_PLAYER_REQUIRED, NETWORK, TIMED_OUT, UNAVAILABLE, CONTEXT_CHANGED
}

data class SourceCheckResult(val status: SourceCheckStatus, val checkedAtMillis: Long)
