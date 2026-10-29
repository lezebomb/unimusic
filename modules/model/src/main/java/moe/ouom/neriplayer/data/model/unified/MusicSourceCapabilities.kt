package moe.ouom.neriplayer.data.model.unified

import java.util.UUID

enum class SourceAudioSupport { LOCAL_FILE, PLATFORM_API, METADATA_ONLY, UNKNOWN }

/** Describes implemented adapters, never a user's entitlement or a promise of playable audio. */
data class MusicSourceCapabilities(val metadataSearch: Boolean, val audio: SourceAudioSupport)

object MusicSourceRegistry {
    private val providers = mapOf(
        "local" to MusicSourceCapabilities(true, SourceAudioSupport.LOCAL_FILE),
        "netease" to MusicSourceCapabilities(true, SourceAudioSupport.PLATFORM_API),
        "gdstudio_netease" to MusicSourceCapabilities(false, SourceAudioSupport.PLATFORM_API),
        "bilibili" to MusicSourceCapabilities(true, SourceAudioSupport.PLATFORM_API),
        "youtube_music" to MusicSourceCapabilities(true, SourceAudioSupport.PLATFORM_API),
        "kuwo" to MusicSourceCapabilities(true, SourceAudioSupport.PLATFORM_API),
        "qqmusic" to MusicSourceCapabilities(true, SourceAudioSupport.PLATFORM_API)
    )
    private val unknown = MusicSourceCapabilities(false, SourceAudioSupport.UNKNOWN)

    fun capabilities(provider: String) = providers[provider] ?: unknown

    /** Product availability is separate from historical identity and implemented adapter metadata. */
    fun isEnabled(provider: String): Boolean = provider in providers && provider !in setOf("qqmusic", "youtube_music")

    fun validKey(key: SongSourceKey): Boolean = key.recordingContext.isEmpty() && when (key.provider) {
        "netease", "kuwo", "gdstudio_netease" -> positiveId(key.resourceId) && key.subResourceId.isEmpty()
        "qqmusic" -> key.resourceId.matches(Regex("[A-Za-z0-9]{14}")) && key.subResourceId.isEmpty()
        "bilibili" -> positiveId(key.resourceId) && positiveId(key.subResourceId)
        "youtube_music" -> key.resourceId.matches(Regex("[A-Za-z0-9_-]{11}")) && key.subResourceId.isEmpty()
        "local" -> runCatching { UUID.fromString(key.resourceId).toString() == key.resourceId }.getOrDefault(false) && key.subResourceId.isEmpty()
        else -> false
    }

    /** Historical metadata can be reconstructed even when this product no longer enables its transport. */
    fun hasAudioAdapter(key: SongSourceKey): Boolean = validKey(key) &&
        capabilities(key.provider).audio in setOf(SourceAudioSupport.LOCAL_FILE, SourceAudioSupport.PLATFORM_API)

    fun canResolveAudio(key: SongSourceKey): Boolean = isEnabled(key.provider) && hasAudioAdapter(key)

    /** Canonical public metadata pages only; source identity grants no audio entitlement. */
    fun publicPageUrl(key: SongSourceKey): String? {
        if (!validKey(key)) return null
        return when (key.provider) {
            "qqmusic" -> "https://y.qq.com/n/ryqq/songDetail/${key.resourceId}"
            "kuwo" -> "https://www.kuwo.cn/play_detail/${key.resourceId}"
            else -> null
        }
    }

    private fun positiveId(value: String) = value.toLongOrNull()?.takeIf { it > 0 }?.toString() == value
}

/** Device-only observation. ADDRESS_RESOLVED means an API returned a URL, not that playback ran. */
enum class SourceCheckStatus {
    ADDRESS_RESOLVED, LOCAL_READABLE, METADATA_ONLY, UNSUPPORTED, DISABLED, OFFLINE,
    USER_REJECTED, METADATA_MISMATCH, AUTH_REQUIRED, NO_PERMISSION, PREVIEW_ONLY,
    EXTERNAL_PLAYER_REQUIRED, NETWORK, TIMED_OUT, UNAVAILABLE, CONTEXT_CHANGED
}

data class SourceCheckResult(val status: SourceCheckStatus, val checkedAtMillis: Long)
