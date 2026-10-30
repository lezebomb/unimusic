package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.common.R as CoreCommonR
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
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.streamOnlyPlaybackUrl
import moe.ouom.neriplayer.data.model.unified.*
import moe.ouom.neriplayer.data.unified.SourceMatchBackend
import moe.ouom.neriplayer.data.unified.sourceCandidateForSong

private val recentNativePrimaryFailures = RecentPrimaryFailureHint()
private val failedKuwoPrimaryMedia = SourceFailureCooldown()

internal fun PlayerManager.recordNativePrimaryMediaFailure(song: SongItem, activeCacheKey: String?, reason: String) {
    val source = sourceCandidateForSong(song)?.key ?: return
    if (isKuwoPrimaryMediaFailure(source, activeCacheKey, reason)) {
        failedKuwoPrimaryMedia.reject(song.stableKey(), source.toString(), durationMs = 10_000)
    }
}

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
        mimeType = "audio/mpeg", cacheKeyOverride = "catalogstream-${source.provider}-${source.resourceId}",
        // Transport provenance belongs to the resolved provider, not the logical song's catalog.
        audioInfo = if (source.provider == "kuwo") PlaybackAudioInfo(
            source = PlaybackAudioSource.KUWO, mimeType = "audio/mpeg"
        ) else null))
}

internal suspend fun PlayerManager.resolveNativeCatalogPlayback(song: SongItem, candidate: RecordingSourceCandidate,
    resolverSideEffects: RefreshResolverSideEffects): SongUrlResult {
    val requestKey = song.stableKey()
    val sourceKey = candidate.key.toString()
    suspend fun remembered(): SongUrlResult.Success? = try {
        tryResolveAutomaticSource(song, resolverSideEffects, local = neteaseLocalSourceFallbackEnabled, online = true)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null } // A temporary projection error must not prevent primary/fresh discovery.
    var rememberedTried = false
    var freshAlternatesTried = false
    val hintSession = sourceResolutionSession(candidate.key.provider)
    if (neteaseAutoSourceSwitchEnabled && candidate.key.provider == "kuwo" && failedKuwoPrimaryMedia.cooling(requestKey, sourceKey)) {
        rememberedTried = true
        val cached = remembered()
        if (cached != null && neteaseAutoSourceSwitchEnabled) return cached
        freshAlternatesTried = true
        val alternate = tryResolveNativeCatalogAlternate(song, resolverSideEffects)
        if (alternate != null && neteaseAutoSourceSwitchEnabled) return alternate
        // No alternate succeeded: still allow a fresh primary URL under the remaining shared deadline.
    } else if (neteaseAutoSourceSwitchEnabled && recentNativePrimaryFailures.recent(requestKey, sourceKey, hintSession)) {
        rememberedTried = true
        remembered()?.let { ready ->
            if (hintSession == sourceResolutionSession(candidate.key.provider) && neteaseAutoSourceSwitchEnabled) return ready
        }
    }
    val primarySession = sourceResolutionSession(candidate.key.provider)
    val primary = try {
        withTimeoutOrNull(sourceStageBudgetMs(3_000, 5_000).coerceAtLeast(1)) { resolveNativeCatalogSource(song, candidate) }
            ?: SourceAttempt.Rejected(SourceFailure.TIMEOUT)
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive();SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
    } catch (_: java.net.SocketTimeoutException) { SourceAttempt.Rejected(SourceFailure.TIMEOUT) }
    catch (_: java.io.IOException) { SourceAttempt.Rejected(SourceFailure.NETWORK) }
    catch (_: Exception) { SourceAttempt.Rejected(SourceFailure.UNAVAILABLE) }
    if (primary is SourceAttempt.Ready && primarySession == sourceResolutionSession(candidate.key.provider)) {
        recentNativePrimaryFailures.clear(requestKey, sourceKey, primarySession)
        return primary.value
    }
    if (neteaseAutoSourceSwitchEnabled) {
        val alternate = (if (!rememberedTried) remembered() else null)
            ?: if (!freshAlternatesTried) tryResolveNativeCatalogAlternate(song, resolverSideEffects) else null
        if (alternate != null) {
            val reason = (primary as? SourceAttempt.Rejected)?.reason
            if (primarySession == sourceResolutionSession(candidate.key.provider) && reason in setOf(
                    SourceFailure.AUTH_REQUIRED, SourceFailure.NO_PERMISSION, SourceFailure.NETWORK,
                    SourceFailure.TIMEOUT, SourceFailure.UNAVAILABLE, SourceFailure.PREVIEW_ONLY)) {
                recentNativePrimaryFailures.record(requestKey, sourceKey, primarySession)
            }
            return alternate
        }
    }
    recentNativePrimaryFailures.clear(requestKey, sourceKey, primarySession)
    val message = if (!MusicSourceRegistry.isEnabled(candidate.key.provider)) CoreCommonR.string.unimusic_source_removed
        else when ((primary as? SourceAttempt.Rejected)?.reason) {
        SourceFailure.NETWORK -> CoreCommonR.string.player_playback_network_error
        SourceFailure.TIMEOUT -> CoreCommonR.string.unimusic_source_request_timeout
        SourceFailure.AUTH_REQUIRED -> if (candidate.key.provider == "qqmusic")
            CoreCommonR.string.unimusic_source_qq_login else CoreCommonR.string.unimusic_source_session_changed
        SourceFailure.NO_PERMISSION -> CoreCommonR.string.unimusic_source_no_full_permission
        SourceFailure.PREVIEW_ONLY -> CoreCommonR.string.unimusic_source_preview_only
        SourceFailure.NO_MATCH, SourceFailure.AMBIGUOUS -> CoreCommonR.string.unimusic_source_recording_mismatch
        SourceFailure.EXTERNAL_PLAYER_REQUIRED -> CoreCommonR.string.unimusic_source_external_player
        SourceFailure.URL_EXPIRED -> CoreCommonR.string.unimusic_source_url_expired
        else -> CoreCommonR.string.unimusic_source_unavailable
    }
    resolverSideEffects.emitError { postPlayerEvent(moe.ouom.neriplayer.data.model.playback.PlayerEvent.ShowError(
        getLocalizedString(message))) }
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
        val original = sourceCandidateForSong(song)?.key
        val neteaseAvailable = { repositories.neteaseCookieRepo.getAuthHealthOnce().state !=
            moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState.Missing }
        val netease = moe.ouom.neriplayer.platform.netease.catalog.NeteaseCatalogProvider(
            fetchPage = { query, limit, offset -> repositories.neteaseClient.searchSongsCancellable(query, limit, offset) },
            sessionIdentity = { repositories.neteaseClient.searchSessionIdentity() }, searchAvailable = neteaseAvailable)
        val available: Map<String, MusicCatalogProvider> = mapOf("netease" to netease,
            "qqmusic" to repositories.qqMusicAudioClient, "kuwo" to repositories.kuwoCatalogProvider)
        val providers = nativeAlternateProviderIds(original?.provider)
            .filter { it != "netease" || neteaseAvailable() }.map { available.getValue(it) }
        racePlaybackSources(providers.map { provider -> suspend {
            var winner: SongUrlResult.Success? = null
            val session = sourceResolutionSession(provider.providerId)
            val tracks = withTimeoutOrNull(1_200) { provider.search(song.recordingMetadata().title, null, 12).tracks }.orEmpty()
            val choice = automaticCatalogChoice(song.recordingMetadata(), tracks,
                original)
            if (choice != null && !RememberedSourceState.cooldown.cooling(song.stableKey(), choice.key.toString())) {
                val stamp = backend.decisionStamp(song, choice.key)
                val previous = backend.match(song, choice.key)
                val coolingMatch = previous != null && RememberedSourceState.cooldown.cooling(song.stableKey(), previous.id)
                if (stamp.decision == SourceMatchDecision.NONE && !coolingMatch &&
                    session == sourceResolutionSession(provider.providerId) && neteaseAutoSourceSwitchEnabled) {
                    val attempt = withTimeoutOrNull(2_600) { resolveSourceCandidate(song, choice, sideEffects, automatic = true) }
                    if (attempt is SourceAttempt.Ready && session == sourceResolutionSession(provider.providerId) &&
                        neteaseAutoSourceSwitchEnabled && isPlaybackNetworkUsable(application)) {
                        val stored = backend.rememberAutomatic(song, choice, stamp)
                        // A playback failure can arrive while this address request is in flight.
                        if (stored != null && !RememberedSourceState.cooldown.cooling(song.stableKey(), stored.id) &&
                            stamp == backend.decisionStamp(song, choice.key) && session == sourceResolutionSession(provider.providerId) &&
                            neteaseAutoSourceSwitchEnabled && isPlaybackNetworkUsable(application)) {
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
