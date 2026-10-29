package moe.ouom.neriplayer.core.player.url

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.network.isPlaybackNetworkUsable
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.core.player.policy.source.sourceStageBudgetMs
import moe.ouom.neriplayer.core.player.policy.source.withCurrentSourceResult
import moe.ouom.neriplayer.core.player.policy.source.raceFreshPlaybackSources
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseAutoBiliSource
import moe.ouom.neriplayer.core.player.resolver.netease.tryResolveNeteaseMatchedLocalSource
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser

internal suspend fun PlayerManager.getNeteaseSongUrl(
    song: SongItem,
    suppressError: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    allowLocalFallback: Boolean = true,
    allowPreviewFallback: Boolean = true,
    allowAutoBiliFallback: Boolean = true
): SongUrlResult {
    val requestSession = neteaseClient.searchSessionIdentity()
    fun current() = requestSession === neteaseClient.searchSessionIdentity()
    return withCurrentSourceResult<SongUrlResult>(::current, SongUrlResult.Failure) {
        resolveNeteaseForSession(song, suppressError, sideEffects.guardedBy(::current),
            allowLocalFallback, allowPreviewFallback, allowAutoBiliFallback, ::current)
    }
}

private suspend fun PlayerManager.resolveNeteaseForSession(
    song: SongItem,
    suppressError: Boolean,
    sideEffects: RefreshResolverSideEffects,
    allowLocalFallback: Boolean,
    allowPreviewFallback: Boolean,
    allowAutoBiliFallback: Boolean,
    isCurrent: () -> Boolean
): SongUrlResult = withContext(Dispatchers.IO) {
    if (!isCurrent()) return@withContext SongUrlResult.Failure
    try {
        val effectiveQuality = effectiveNeteaseQuality()
        val qualityCandidates = buildNeteaseQualityCandidates(effectiveQuality)
        var previewFallback: SongUrlResult.Success? = null
        var lastFailureReason: NeteasePlaybackResponseParser.FailureReason? = null
        var requiresLogin = false
        var primaryTimedOut = false
        val primaryBudget = sourceStageBudgetMs(2_500, 8_000)
        if (primaryBudget <= 0) return@withContext SongUrlResult.Failure
        val deadline = SystemClock.elapsedRealtime() + primaryBudget

        for ((index, quality) in qualityCandidates.withIndex()) {
            if (!isCurrent()) return@withContext SongUrlResult.Failure
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) { primaryTimedOut = true; break }
            val resp = withTimeoutOrNull(minOf(sourceStageBudgetMs(1_500, 3_000), remainingMs)) {
                neteaseClient.getSongDownloadUrlCancellable(song.id, level = quality)
            }
            if (!isCurrent()) return@withContext SongUrlResult.Failure
            if (resp == null) { primaryTimedOut = true; break }
            NPLogger.d("NERI-PlayerManager", "Netease URL response: id=${song.id}, requestedQuality=$quality")

            when (val parsed = NeteasePlaybackResponseParser.parsePlayback(resp, song.durationMs)) {
                is NeteasePlaybackResponseParser.PlaybackResult.RequiresLogin -> {
                    requiresLogin = true
                    if (shouldRetryNeteaseWithLowerQualityAfterLogin(
                            qualityIndex = index,
                            lastQualityIndex = qualityCandidates.lastIndex
                        )
                    ) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质需要登录，继续尝试更低音质: id=${song.id}, level=$quality"
                        )
                        continue
                    }
                    break
                }

                is NeteasePlaybackResponseParser.PlaybackResult.Success -> {
                    val success = buildNeteaseSuccessResult(
                        parsed = parsed,
                        resolvedQualityKey = quality,
                        fallbackDurationMs = song.durationMs,
                        getLocalizedString = { getLocalizedString(it) }
                    ).let { result ->
                        if (parsed.notice == NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                            result.copy(
                                cacheKeyOverride = buildNeteasePreviewCacheKey(
                                    songId = song.id,
                                    preferredQuality = quality
                                )
                            )
                        } else {
                            result
                        }
                    }
                    if (parsed.notice != NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP) {
                        if (quality != effectiveQuality) {
                            NPLogger.w(
                                "NERI-PlayerManager",
                                "当前音质不可用，已自动降级: id=${song.id}, preferred=$effectiveQuality, resolved=$quality"
                            )
                        }
                        return@withContext success
                    }

                    previewFallback = success
                    if (index < qualityCandidates.lastIndex) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质仅返回试听片段，继续尝试更低音质: id=${song.id}, level=$quality"
                        )
                        continue
                    }
                }

                is NeteasePlaybackResponseParser.PlaybackResult.Failure -> {
                    lastFailureReason = parsed.reason
                    if (index < qualityCandidates.lastIndex &&
                        shouldRetryNeteaseWithLowerQuality(parsed.reason)
                    ) {
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "当前音质不可播放，继续尝试更低音质: id=${song.id}, level=$quality, reason=${parsed.reason}"
                        )
                        continue
                    }
                    break
                }
            }
        }

        if (!isCurrent()) return@withContext SongUrlResult.Failure
        // These are independent local/opt-in sources, not retries that bypass Netease access checks.
        resolveNeteaseAlternates(song, sideEffects, allowLocalFallback, allowAutoBiliFallback)?.let {
            return@withContext it
        }

        if (allowPreviewFallback) {
            previewFallback?.let { return@withContext it }
        }

        if (requiresLogin && !primaryTimedOut) {
            return@withContext SongUrlResult.RequiresLogin
        }

        if (!suppressError) {
            val messageRes = if (primaryTimedOut) CoreCommonR.string.unimusic_source_request_timeout else when (lastFailureReason) {
                NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION ->
                    CoreCommonR.string.player_netease_no_permission_switch_platform
                NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL,
                NeteasePlaybackResponseParser.FailureReason.UNKNOWN,
                null -> CoreCommonR.string.error_no_play_url
            }
            sideEffects.emitError {
                postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(messageRes)))
            }
        }
        SongUrlResult.Failure
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (!isCurrent()) return@withContext SongUrlResult.Failure
        resolveNeteaseAlternates(song, sideEffects, allowLocalFallback, allowAutoBiliFallback)?.let {
            return@withContext it
        }
        NPLogger.w("NERI-PlayerManager", "Netease URL resolution failed: type=${e.javaClass.simpleName}")
        if (!suppressError && isPlaybackNetworkUsable(application)) {
            sideEffects.emitError {
                postPlayerEvent(
                    PlayerEvent.ShowError(
                        getLocalizedString(when (e) {
                            is java.net.SocketTimeoutException -> CoreCommonR.string.unimusic_source_request_timeout
                            is java.io.IOException -> CoreCommonR.string.player_playback_network_error
                            else -> CoreCommonR.string.error_no_play_url
                        })
                    )
                )
            }
        }
        SongUrlResult.Failure
    }
}

private suspend fun PlayerManager.resolveNeteaseAlternates(
    song: SongItem, sideEffects: RefreshResolverSideEffects, local: Boolean, online: Boolean
): SongUrlResult? {
    if (local || online) {
        try { tryResolveAutomaticSource(song, sideEffects, local, online)?.let { return it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { NPLogger.w("NERI-PlayerManager", "Automatic source mapping unavailable") }
    }
    if (local) {
        try { tryResolveNeteaseMatchedLocalSource(song)?.let { return it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { NPLogger.w("NERI-PlayerManager", "Local alternate is temporarily unreadable") }
    }
    if (online && isPlaybackNetworkUsable(application)) {
        val sessions = listOf("netease", "kuwo", "bilibili").associateWith { sourceResolutionSession(it) }
        fun current() = neteaseAutoSourceSwitchEnabled && isPlaybackNetworkUsable(application) &&
            sessions.all { (provider, session) -> session == sourceResolutionSession(provider) }
        return raceFreshPlaybackSources<SongUrlResult.Success>(
            tasks = listOf(
                { isolated -> tryResolveNativeCatalogAlternate(song, isolated) },
                { isolated -> tryResolveNeteaseAutoBiliSource(song, isolated) as? SongUrlResult.Success }
            ),
            isCurrent = ::current,
            applyWinner = { ready -> ready.durationMs?.takeIf { it > 0L }?.let { duration ->
                sideEffects.updateDuration { maybeUpdateSongDuration(song, duration) }
            } }
        )
    }
    return null
}
