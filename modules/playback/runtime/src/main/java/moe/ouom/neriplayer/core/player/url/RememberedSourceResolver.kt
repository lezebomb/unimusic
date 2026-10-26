package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.network.isPlaybackNetworkUsable
import moe.ouom.neriplayer.core.player.policy.source.*
import moe.ouom.neriplayer.core.player.resolver.netease.resolveStrictBiliSource
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseMatchedLocalSource
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoItem
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.unified.RecordingSourceMatch
import moe.ouom.neriplayer.data.model.unified.SourceMatchDecision
import moe.ouom.neriplayer.data.unified.SourceMatchBackend
import moe.ouom.neriplayer.data.unified.sourceCandidateForSong
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate

internal object RememberedSourceState {
    val cooldown = SourceFailureCooldown()
    fun failed(song: SongItem, cacheKey: String?, reason: String) {
        if (cacheKey?.startsWith("match-") != true || reason.endsWith("offline_cache") ||
            !(reason.startsWith("playback_error_") || reason.startsWith("startup_stall_") || reason.startsWith("runtime_stall_"))) return
        // Match IDs are canonical UUIDs: take the fixed prefix, never parse signed media URLs.
        cacheKey.removePrefix("match-").take(36).takeIf { it.length == 36 }
            ?.let { cooldown.reject(song.stableKey(), it) }
    }
}

internal suspend fun PlayerManager.tryResolveRememberedSource(song: SongItem,
    sideEffects: RefreshResolverSideEffects): SongUrlResult.Success? {
    try {
        val backend = SourceMatchBackend(application)
        val confirmed = backend.matches(song).filter { it.current && it.decision == SourceMatchDecision.CONFIRMED }
        if (confirmed.isEmpty()) return null
        val candidates = flow {
            confirmed.forEach { match ->
                val provider = match.candidate.key.provider
                val allowed = (provider == "local" || isPlaybackNetworkUsable(application)) &&
                    (provider != "youtube_music" || YouTubeFeatureGate.isEnabled())
                emit(PlaybackSourceCandidate(match.id, allowed) {
                    val result = resolveRememberedCandidate(song, match, sideEffects)
                    // A rejection/reset while the network was in flight must win over the old result.
                    val current = backend.matches(song).any { it.id == match.id && it.revision == match.revision &&
                        it.current && it.decision == SourceMatchDecision.CONFIRMED }
                    if (result != null && current) SourceAttempt.Ready(result.copy(
                        cacheKeyOverride = "match-${match.id}-${result.cacheKeyOverride ?: provider}"))
                    else SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
                })
            }
        }
        return PlaybackSourceResolver(RememberedSourceState.cooldown).resolve(song.stableKey(), candidates,
            totalBudgetMs = 8_000, candidateBudgetMs = 4_000).value
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        NPLogger.w("NERI-PlayerManager", "Remembered source unavailable: type=${error.javaClass.simpleName}")
        return null
    }
}

private suspend fun PlayerManager.resolveRememberedCandidate(subject: SongItem, match: RecordingSourceMatch,
    sideEffects: RefreshResolverSideEffects): SongUrlResult.Success? {
    val source = match.candidate.key
    val meta = match.candidate.metadata
    val result: SongUrlResult? = when (source.provider) {
        "bilibili" -> {
            val item = SearchVideoItem(source.resourceId.toLong(), "", "", "", "", 0, "", 0, null, null)
            (resolveStrictBiliSource(subject, item, sideEffects, source.subResourceId.toLong()) as? SourceAttempt.Ready)?.value
        }
        "local" -> {
            val local = localRepo.playlists.value.asSequence().flatMap { it.songs.asSequence() }
                .firstOrNull { sourceCandidateForSong(it)?.key == source } ?: return null
            tryResolveNeteaseMatchedLocalSource(subject, local)
        }
        "netease" -> {
            val item = SongItem(source.resourceId.toLong(), meta.title, meta.artist, meta.album, 0, meta.durationMs,
                null, channelId = "netease", audioId = source.resourceId)
            (getNeteaseSongUrl(item, suppressError = true, sideEffects = sideEffects, allowLocalFallback = false,
                allowPreviewFallback = false, allowAutoBiliFallback = false) as? SongUrlResult.Success)
                ?.copy(cacheKeyOverride = computeCacheKey(item))
        }
        "youtube_music" -> {
            val item = SongItem(stableYouTubeMusicId(source.resourceId), meta.title, meta.artist, meta.album, 0,
                meta.durationMs, null, mediaUri = buildYouTubeMusicMediaUri(source.resourceId),
                channelId = "youtubeMusic", audioId = source.resourceId)
            (getYouTubeMusicAudioUrl(item, suppressError = true, sideEffects = sideEffects) as? SongUrlResult.Success)
                ?.takeIf { !it.isPreviewClip && (it.durationMs ?: 0) > 0 &&
                    kotlin.math.abs(requireNotNull(it.durationMs) - subject.durationMs) <= 4_000 }
                ?.copy(cacheKeyOverride = computeCacheKey(item))
        }
        else -> null
    }
    return result as? SongUrlResult.Success
}
