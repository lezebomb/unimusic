package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.network.isPlaybackNetworkUsable
import moe.ouom.neriplayer.core.player.policy.source.*
import moe.ouom.neriplayer.core.player.policy.source.RecordingMatchPolicy
import moe.ouom.neriplayer.core.player.policy.source.RecordingMetadata
import moe.ouom.neriplayer.core.player.resolver.netease.recordingMetadata
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.streamOnlyPlaybackUrl
import moe.ouom.neriplayer.data.model.unified.*
import moe.ouom.neriplayer.data.unified.SourceMatchBackend
import moe.ouom.neriplayer.data.unified.sourceCandidateForSong

internal suspend fun PlayerManager.resolveNativeCatalogSource(subject: SongItem,
    candidate: RecordingSourceCandidate): SourceAttempt<SongUrlResult.Success> {
    val source = candidate.key
    if (!MusicSourceRegistry.canResolveAudio(source)) return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
    val repositories = PlayerDependencies.repositories
    val session = sourceResolutionSession(source.provider)
    val response = when (source.provider) {
        "qqmusic" -> repositories.qqMusicAuthRepo.resolveAudio(source.resourceId, repositories.qqMusicAudioClient)
        "kuwo" -> repositories.kuwoAudioClient.resolve(source.resourceId)
        else -> return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
    }
    if (session != sourceResolutionSession(source.provider)) return SourceAttempt.Rejected(SourceFailure.AUTH_REQUIRED)
    if (!isPlaybackNetworkUsable(application)) return SourceAttempt.Rejected(SourceFailure.NETWORK)
    val metadata = response.metadata
    if (metadata != null && !RecordingMatchPolicy.assess(subject.recordingMetadata(),
            RecordingMetadata(metadata.title, metadata.artist, metadata.durationMs, metadata.version, metadata.album)).accepted) {
        return SourceAttempt.Rejected(SourceFailure.NO_MATCH)
    }
    val url = response.url
    if (response.status != SourceCheckStatus.ADDRESS_RESOLVED || metadata == null || url == null) {
        return SourceAttempt.Rejected(when (response.status) {
            SourceCheckStatus.AUTH_REQUIRED, SourceCheckStatus.CONTEXT_CHANGED -> SourceFailure.AUTH_REQUIRED
            SourceCheckStatus.NO_PERMISSION -> SourceFailure.NO_PERMISSION
            SourceCheckStatus.PREVIEW_ONLY -> SourceFailure.PREVIEW_ONLY
            SourceCheckStatus.EXTERNAL_PLAYER_REQUIRED -> SourceFailure.EXTERNAL_PLAYER_REQUIRED
            SourceCheckStatus.METADATA_MISMATCH -> SourceFailure.NO_MATCH
            else -> SourceFailure.UNAVAILABLE
        })
    }
    return SourceAttempt.Ready(SongUrlResult.Success(streamOnlyPlaybackUrl(url), durationMs = metadata.durationMs,
        mimeType = "audio/mpeg", cacheKeyOverride = "catalogstream-${source.provider}-${source.resourceId}"))
}

internal suspend fun PlayerManager.resolveNativeCatalogPlayback(song: SongItem, candidate: RecordingSourceCandidate,
    resolverSideEffects: RefreshResolverSideEffects): SongUrlResult {
    val primary = try {
        withTimeoutOrNull(sourceStageBudgetMs(3_000, 5_000).coerceAtLeast(1)) { resolveNativeCatalogSource(song, candidate) }
    } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive();null }
    catch (_: Exception) { null }
    if (primary is SourceAttempt.Ready) return primary.value
    if (neteaseAutoSourceSwitchEnabled) tryResolveNativeCatalogAlternate(song, resolverSideEffects)?.let { return it }
    resolverSideEffects.emitError { postPlayerEvent(moe.ouom.neriplayer.data.model.playback.PlayerEvent.ShowError(
        if (primary is SourceAttempt.Rejected && primary.reason == SourceFailure.AUTH_REQUIRED) "此音源需要登录，请在设置中登录 QQ 音乐"
        else "当前账号无完整播放权限，或音源暂时不可用")) }
    return SongUrlResult.Failure
}

/** Two independent normal providers race under the existing shared budget. No serial timeout stack.
 * Search metadata is gated first; each provider contributes at most one unambiguous recording.
 * First eligible full-address result wins and immediately cancels the other in-flight call.
 */
internal suspend fun PlayerManager.tryResolveNativeCatalogAlternate(song: SongItem,
    sideEffects: RefreshResolverSideEffects): SongUrlResult.Success? {
    if (!neteaseAutoSourceSwitchEnabled || !isPlaybackNetworkUsable(application)) return null
    val stageBudget = sourceStageBudgetMs(3_800, 4_500)
    if (stageBudget <= 0) return null
    return withTimeoutOrNull(stageBudget) {
        val repositories = PlayerDependencies.repositories
        val backend = SourceMatchBackend(application)
        val providers = listOf(repositories.qqMusicAudioClient, repositories.kuwoCatalogProvider)
        racePlaybackSources(providers.map { provider -> suspend {
            var winner: SongUrlResult.Success? = null
            val session = sourceResolutionSession(provider.providerId)
            val tracks = withTimeoutOrNull(1_200) { provider.search(song.recordingMetadata().title, null, 12).tracks }.orEmpty()
            val choice = automaticCatalogChoice(song.recordingMetadata(), tracks,
                sourceCandidateForSong(song)?.key)
            if (choice != null && !RememberedSourceState.cooldown.cooling(song.stableKey(), choice.key.toString())) {
                val stamp = backend.decisionStamp(song, choice.key)
                val previous = backend.match(song, choice.key)
                val coolingMatch = previous != null && RememberedSourceState.cooldown.cooling(song.stableKey(), previous.id)
                if (stamp.decision == SourceMatchDecision.NONE && !coolingMatch) {
                    val attempt = withTimeoutOrNull(2_600) { resolveSourceCandidate(song, choice, sideEffects, automatic = true) }
                    if (attempt is SourceAttempt.Ready && session == sourceResolutionSession(provider.providerId) &&
                        neteaseAutoSourceSwitchEnabled && isPlaybackNetworkUsable(application)) {
                        val stored = backend.rememberAutomatic(song, choice, stamp)
                        // A playback failure can arrive while this address request is in flight.
                        if (stored != null && !RememberedSourceState.cooldown.cooling(song.stableKey(), stored.id)) {
                            winner = attempt.value.copy(cacheKeyOverride = "match-${stored.id}-${attempt.value.cacheKeyOverride}")
                        }
                    } else if (attempt !is SourceAttempt.Ready) {
                        val decisionCurrent = stamp == backend.decisionStamp(song, choice.key)
                        val action = automaticSourceFailureAction(
                            (attempt as? SourceAttempt.Rejected)?.reason ?: SourceFailure.TIMEOUT,
                            decisionCurrent && session == sourceResolutionSession(provider.providerId) &&
                                neteaseAutoSourceSwitchEnabled && isPlaybackNetworkUsable(application))
                        // Rights/metadata rejection may be reconsidered after login; only transport failures cool discovery.
                        if (action.coolSource) RememberedSourceState.cooldown.reject(song.stableKey(), choice.key.toString())
                    }
                }
            }
            winner
        } })
    }
}
