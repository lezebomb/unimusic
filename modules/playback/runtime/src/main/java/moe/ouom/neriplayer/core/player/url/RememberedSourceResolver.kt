package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asFlow
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.network.isPlaybackNetworkUsable
import moe.ouom.neriplayer.core.player.policy.source.*
import moe.ouom.neriplayer.core.player.resolver.netease.resolveStrictBiliSource
import moe.ouom.neriplayer.core.player.resolver.netease.recordingMetadata
import moe.ouom.neriplayer.core.player.resolver.netease.biliRecordingMetadata
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseMatchedLocalSource
import moe.ouom.neriplayer.core.player.resolver.netease.NeteaseAutoSourceState
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoItem
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate
import moe.ouom.neriplayer.data.model.unified.SourceMatchDecision
import moe.ouom.neriplayer.data.model.unified.RecordingSourceMatch
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

private val gdStudioAudioClient by lazy { moe.ouom.neriplayer.platform.gdstudio.api.GdStudioClient() }

internal suspend fun PlayerManager.tryResolveRememberedSource(unifiedSongId: String, song: SongItem,
    sideEffects: RefreshResolverSideEffects): SongUrlResult.Success? =
    tryResolveStoredSource(song, sideEffects, unifiedSongId)

/** Runs only after the original source failed; never promotes a search result or explicit probe. */
internal suspend fun PlayerManager.tryResolveAutomaticSource(song: SongItem,
    sideEffects: RefreshResolverSideEffects, local: Boolean, online: Boolean): SongUrlResult.Success? =
    tryResolveStoredSource(song, sideEffects, null, buildSet {
        if (local) add("local")
        if (online) addAll(setOf("bilibili", "qqmusic", "kuwo", "netease"))
    })

private fun PlayerManager.automaticSourceEnabled(provider: String): Boolean = when (provider) {
    "local" -> neteaseLocalSourceFallbackEnabled
    "netease" -> neteaseAutoSourceSwitchEnabled && isPlaybackNetworkUsable(application) &&
        PlayerDependencies.repositories.neteaseCookieRepo.getAuthHealthOnce().state !=
            moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState.Missing
    "bilibili", "qqmusic", "kuwo" -> neteaseAutoSourceSwitchEnabled && isPlaybackNetworkUsable(application)
    else -> false
}

private fun coolAutomaticBili(song: SongItem, match: RecordingSourceMatch) {
    if (match.candidate.key.provider == "bilibili" && RememberedSourceState.cooldown.cooling(song.stableKey(), match.id)) {
        // Do not spend another network request on the same failed physical source during discovery.
        NeteaseAutoSourceState.cooldown.reject(NeteaseAutoSourceState.key(song), "av${match.candidate.key.resourceId}")
    }
}

private suspend fun PlayerManager.tryResolveStoredSource(song: SongItem, sideEffects: RefreshResolverSideEffects,
    unifiedSongId: String?, allowedAutomaticProviders: Set<String> = emptySet()): SongUrlResult.Success? {
    try {
        val backend = SourceMatchBackend(application)
        val automatic = unifiedSongId == null
        val stored = if (automatic) backend.automaticMatches(song).filter {
            it.candidate.key.provider in allowedAutomaticProviders && automaticSourceEnabled(it.candidate.key.provider) }
            else backend.matches(requireNotNull(unifiedSongId), song).filter { it.current && it.decision == SourceMatchDecision.CONFIRMED }
        if (automatic) stored.forEach { coolAutomaticBili(song, it) }
        val matches = if (automatic) stored.filterNot { RememberedSourceState.cooldown.cooling(song.stableKey(), it.id) }.take(2)
            else stored
        if (matches.isEmpty()) return null
        val candidates = matches.map { match ->
                val provider = match.candidate.key.provider
                val allowed = (provider == "local" || isPlaybackNetworkUsable(application)) &&
                    (provider != "youtube_music" || YouTubeFeatureGate.isEnabled())
                PlaybackSourceCandidate(match.id, allowed) {
                    val session = sourceResolutionSession(provider)
                    val attempt = try { resolveSourceCandidate(song, match.candidate, sideEffects, automatic) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (network: java.io.IOException) { throw network }
                    catch (_: Exception) { SourceAttempt.Rejected(SourceFailure.UNAVAILABLE) }
                    // A rejection/reset while the network was in flight must win over the old result.
                    val refreshed = backend.match(song, match.candidate.key)
                    val current = refreshed != null && refreshed.id == match.id && refreshed.songId == match.songId &&
                        refreshed.revision == match.revision && refreshed.current &&
                        refreshed.decision == (if (automatic) SourceMatchDecision.NONE else SourceMatchDecision.CONFIRMED)
                    if (automatic && attempt is SourceAttempt.Rejected) {
                        val action = automaticSourceFailureAction(attempt.reason, current &&
                            session == sourceResolutionSession(provider) && automaticSourceEnabled(provider))
                        if (action.invalidateMapping) backend.invalidateAutomatic(song, match)
                        // Room invalidation can suspend; recheck account/switch before changing in-memory cooldowns.
                        if (action.coolSource && session == sourceResolutionSession(provider) && automaticSourceEnabled(provider)) {
                            RememberedSourceState.cooldown.reject(song.stableKey(), match.id)
                            coolAutomaticBili(song, match)
                        }
                    }
                    if (session != sourceResolutionSession(provider)) SourceAttempt.Rejected(SourceFailure.AUTH_REQUIRED)
                    else if (automatic && !automaticSourceEnabled(provider)) SourceAttempt.Rejected(SourceFailure.NO_PERMISSION)
                    else if (provider != "local" && !isPlaybackNetworkUsable(application)) SourceAttempt.Rejected(SourceFailure.NETWORK)
                    else if (!current) SourceAttempt.Rejected(SourceFailure.NO_MATCH)
                    else when (attempt) {
                        is SourceAttempt.Ready -> SourceAttempt.Ready(attempt.value.copy(
                            cacheKeyOverride = attempt.value.cacheKeyOverride?.takeIf { it.startsWith("match-${match.id}-") }
                                ?: "match-${match.id}-${attempt.value.cacheKeyOverride ?: provider}"))
                        is SourceAttempt.Rejected -> attempt
                    }
                }
        }
        val hasPublicSource = matches.any { it.candidate.key.provider in setOf("gdstudio_netease", "qqmusic", "kuwo") }
        val budget = if (automatic) sourceStageBudgetMs(1_200, 2_500)
            else if (hasPublicSource) sourceStageBudgetMs(5_000, 8_000) else sourceStageBudgetMs(2_000, 8_000)
        if (budget <= 0) return null
        val resolver = PlaybackSourceResolver(RememberedSourceState.cooldown)
        val candidateBudget = (if (automatic) sourceStageBudgetMs(1_000, 2_000)
            else if (hasPublicSource) sourceStageBudgetMs(4_500, 4_500)
            else sourceStageBudgetMs(1_500, 4_000)).coerceAtLeast(1)
        val result = if (automatic && matches.none { it.candidate.key.provider == "local" })
            resolver.resolveParallel(song.stableKey(), candidates, budget, candidateBudget)
        else resolver.resolve(song.stableKey(), candidates.asFlow(), budget, candidateBudget)
        if (automatic) matches.forEach { coolAutomaticBili(song, it) }
        return result.value
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        NPLogger.w("NERI-PlayerManager", "Remembered source unavailable: type=${error.javaClass.simpleName}")
        return null
    }
}

internal suspend fun PlayerManager.resolveSourceCandidate(subject: SongItem, candidate: RecordingSourceCandidate,
    sideEffects: RefreshResolverSideEffects, automatic: Boolean = false): SourceAttempt<SongUrlResult.Success> {
    val source = candidate.key
    val meta = candidate.metadata
    val result: SongUrlResult.Success = when (source.provider) {
        "qqmusic", "kuwo" -> return resolveNativeCatalogSource(subject, candidate)
        "gdstudio_netease" -> {
            val raw = neteaseClient.getSongDetailCancellable(listOf(source.resourceId.toLong()))
            neteaseDetailsAccessFailure(raw)?.let { return SourceAttempt.Rejected(it) }
            val fresh = refreshedNeteaseSource(raw, source.resourceId)
                ?: return SourceAttempt.Rejected(SourceFailure.NO_MATCH)
            if (!RecordingMatchPolicy.assess(subject.recordingMetadata(), fresh.second).accepted) {
                return SourceAttempt.Rejected(SourceFailure.NO_MATCH)
            }
            val audio = gdStudioAudioClient.resolveNetease(source.resourceId)
                ?: return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            SongUrlResult.Success(
                url = moe.ouom.neriplayer.data.model.playback.streamOnlyPlaybackUrl(audio.url),
                durationMs = fresh.first.durationMs, mimeType = audio.mimeType,
                noticeMessage = getLocalizedString(moe.ouom.neriplayer.common.R.string.unimusic_sources_gd_notice),
                audioInfo = moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo(
                    source = moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource.NETEASE,
                    qualityLabel = "GD Studio · ${audio.bitrateKbps} kbps", bitrateKbps = audio.bitrateKbps,
                    mimeType = audio.mimeType, codecLabel = "MP3"),
                cacheKeyOverride = "gdstream-netease-${source.resourceId}-${audio.bitrateKbps}"
            )
        }
        "bilibili" -> {
            val item = SearchVideoItem(source.resourceId.toLong(), "", "", "", "", 0, "", 0, null, null)
            return resolveStrictBiliSource(subject, item, sideEffects, source.subResourceId.toLong(), automatic)
        }
        "local" -> {
            if (!localRepo.awaitInitialized()) return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            val local = localRepo.playlists.value.asSequence().flatMap { it.songs.asSequence() }
                .firstOrNull { sourceCandidateForSong(it)?.key == source } ?: return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            (tryResolveNeteaseMatchedLocalSource(subject, local, automatic) as? SongUrlResult.Success)
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
            val response = when (val resolved = getYouTubeMusicAudioUrl(item, suppressError = true, sideEffects = sideEffects)) {
                is SongUrlResult.Success -> resolved
                SongUrlResult.RequiresLogin -> return SourceAttempt.Rejected(SourceFailure.AUTH_REQUIRED)
                else -> return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            }
            if (response.isPreviewClip) return SourceAttempt.Rejected(SourceFailure.PREVIEW_ONLY)
            val duration = response.durationMs?.takeIf { it > 0 } ?: return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
            if (kotlin.math.abs(duration - subject.durationMs) > 4_000) return SourceAttempt.Rejected(SourceFailure.NO_MATCH)
            response.copy(cacheKeyOverride = computeCacheKey(item))
        }
        else -> return SourceAttempt.Rejected(SourceFailure.EXTERNAL_PLAYER_REQUIRED)
    }
    return SourceAttempt.Ready(result)
}
