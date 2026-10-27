package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.network.isPlaybackNetworkUsable
import moe.ouom.neriplayer.core.player.policy.source.*
import moe.ouom.neriplayer.core.player.resolver.netease.resolveStrictBiliSource
import moe.ouom.neriplayer.core.player.resolver.netease.recordingMetadata
import moe.ouom.neriplayer.core.player.resolver.netease.biliRecordingMetadata
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseMatchedLocalSource
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoItem
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate
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

internal suspend fun PlayerManager.tryResolveRememberedSource(unifiedSongId: String, song: SongItem,
    sideEffects: RefreshResolverSideEffects): SongUrlResult.Success? {
    try {
        val backend = SourceMatchBackend(application)
        val confirmed = backend.matches(unifiedSongId, song).filter { it.current && it.decision == SourceMatchDecision.CONFIRMED }
        if (confirmed.isEmpty()) return null
        val candidates = flow {
            confirmed.forEach { match ->
                val provider = match.candidate.key.provider
                val allowed = (provider == "local" || isPlaybackNetworkUsable(application)) &&
                    (provider != "youtube_music" || YouTubeFeatureGate.isEnabled())
                emit(PlaybackSourceCandidate(match.id, allowed) {
                    val session = sourceResolutionSession(provider)
                    val attempt = try { resolveSourceCandidate(song, match.candidate, sideEffects) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (network: java.io.IOException) { throw network }
                    catch (_: Exception) { SourceAttempt.Rejected(SourceFailure.UNAVAILABLE) }
                    // A rejection/reset while the network was in flight must win over the old result.
                    val current = backend.matches(unifiedSongId, song).any { it.id == match.id && it.revision == match.revision &&
                        it.current && it.decision == SourceMatchDecision.CONFIRMED }
                    if (session != sourceResolutionSession(provider)) SourceAttempt.Rejected(SourceFailure.AUTH_REQUIRED)
                    else if (provider != "local" && !isPlaybackNetworkUsable(application)) SourceAttempt.Rejected(SourceFailure.NETWORK)
                    else if (!current) SourceAttempt.Rejected(SourceFailure.NO_MATCH)
                    else when (attempt) {
                        is SourceAttempt.Ready -> SourceAttempt.Ready(attempt.value.copy(
                            cacheKeyOverride = "match-${match.id}-${attempt.value.cacheKeyOverride ?: provider}"))
                        is SourceAttempt.Rejected -> attempt
                    }
                })
            }
        }
        val budget = sourceStageBudgetMs(2_000, 8_000)
        if (budget <= 0) return null
        return PlaybackSourceResolver(RememberedSourceState.cooldown).resolve(song.stableKey(), candidates,
            totalBudgetMs = budget, candidateBudgetMs = sourceStageBudgetMs(1_500, 4_000).coerceAtLeast(1)).value
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        NPLogger.w("NERI-PlayerManager", "Remembered source unavailable: type=${error.javaClass.simpleName}")
        return null
    }
}

internal suspend fun PlayerManager.resolveSourceCandidate(subject: SongItem, candidate: RecordingSourceCandidate,
    sideEffects: RefreshResolverSideEffects): SourceAttempt<SongUrlResult.Success> {
    val source = candidate.key
    val meta = candidate.metadata
    val result: SongUrlResult.Success = when (source.provider) {
        "bilibili" -> {
            val item = SearchVideoItem(source.resourceId.toLong(), "", "", "", "", 0, "", 0, null, null)
            return resolveStrictBiliSource(subject, item, sideEffects, source.subResourceId.toLong())
        }
        "local" -> {
            if (!localRepo.awaitInitialized()) return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            val local = localRepo.playlists.value.asSequence().flatMap { it.songs.asSequence() }
                .firstOrNull { sourceCandidateForSong(it)?.key == source } ?: return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            (tryResolveNeteaseMatchedLocalSource(subject, local) as? SongUrlResult.Success)
                ?: return SourceAttempt.Rejected(SourceFailure.NO_PERMISSION)
        }
        "netease" -> {
            val raw = neteaseClient.getSongDetailCancellable(listOf(source.resourceId.toLong()))
            neteaseDetailsAccessFailure(raw)?.let { return SourceAttempt.Rejected(it) }
            val fresh = refreshedNeteaseSource(raw, source.resourceId)
                ?.takeIf { RecordingMatchPolicy.assess(subject.recordingMetadata(), it.second).accepted }
                ?: return SourceAttempt.Rejected(SourceFailure.NO_MATCH)
            val item = fresh.first
            when (val response = getNeteaseSongUrl(item, suppressError = true, sideEffects = sideEffects, allowLocalFallback = false,
                allowPreviewFallback = false, allowAutoBiliFallback = false)) {
                is SongUrlResult.Success -> response.copy(cacheKeyOverride = computeCacheKey(item))
                is SongUrlResult.RequiresLogin -> return SourceAttempt.Rejected(SourceFailure.AUTH_REQUIRED)
                else -> return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            }
        }
        "youtube_music" -> {
            val fresh = youtubeMusicClient.getVideoMetadata(source.resourceId) ?: return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            val expected = subject.recordingMetadata()
            val artist = fresh.authorName.removeSuffix(" - Topic").trim()
            val metadata = biliRecordingMetadata(expected, fresh.title, fresh.title, artist, meta.durationMs)
            if (!RecordingMatchPolicy.assess(expected, metadata).accepted) return SourceAttempt.Rejected(SourceFailure.NO_MATCH)
            val item = SongItem(stableYouTubeMusicId(source.resourceId), meta.title, meta.artist, meta.album, 0,
                meta.durationMs, null, mediaUri = buildYouTubeMusicMediaUri(source.resourceId),
                channelId = "youtubeMusic", audioId = source.resourceId)
            val response = getYouTubeMusicAudioUrl(item, suppressError = true, sideEffects = sideEffects) as? SongUrlResult.Success
                ?: return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            if (response.isPreviewClip) return SourceAttempt.Rejected(SourceFailure.PREVIEW_ONLY)
            val duration = response.durationMs?.takeIf { it > 0 } ?: return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            if (kotlin.math.abs(duration - subject.durationMs) > 4_000) return SourceAttempt.Rejected(SourceFailure.NO_MATCH)
            response.copy(cacheKeyOverride = computeCacheKey(item))
        }
        else -> return SourceAttempt.Rejected(SourceFailure.EXTERNAL_PLAYER_REQUIRED)
    }
    return SourceAttempt.Ready(result)
}
