package moe.ouom.neriplayer.core.player.resolver.unm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.network.isPlaybackNetworkUsable
import moe.ouom.neriplayer.core.player.policy.source.SourceFailure
import moe.ouom.neriplayer.core.player.policy.source.sourceStageBudgetMs
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.url.sourceResolutionSession
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.deriveCodecLabel
import moe.ouom.neriplayer.data.model.playback.streamOnlyPlaybackUrl
import moe.ouom.neriplayer.platform.unm.api.UnmBridgeClient
import moe.ouom.neriplayer.platform.unm.api.UnmBridgeFailure
import moe.ouom.neriplayer.platform.unm.api.UnmBridgeOutcome

/** Provider key used only for session bookkeeping; matches never create a new catalogue identity. */
internal const val UNM_PROVIDER = "unm_netease"

private val unmBridgeClient by lazy { UnmBridgeClient() }

/** Result of asking the bridge, keeping the reason so a checked source can report it. */
internal sealed interface UnmSourceResult {
    data class Ready(val success: SongUrlResult.Success) : UnmSourceResult
    data class Unavailable(val failure: SourceFailure) : UnmSourceResult
}

/**
 * Races the bridge inside the caller's budget; only the audio is handed back.
 *
 * Every failure is a silent `null` so the remaining candidates keep racing.
 */
internal suspend fun PlayerManager.tryResolveUnmBridgeSource(
    song: SongItem,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult.Success? = (resolveUnmBridgeSource(song, sideEffects) as? UnmSourceResult.Ready)?.success

/**
 * Asks the user's own matching bridge for a full recording of the same Netease track.
 *
 * The bridge answers by Netease id, so identity is inherited from the song being played; the
 * bridge's byte size and bitrate are converted into a duration and compared against the catalogue
 * duration before anything is returned.
 */
internal suspend fun PlayerManager.resolveUnmBridgeSource(
    song: SongItem,
    sideEffects: RefreshResolverSideEffects
): UnmSourceResult {
    if (!unmBridgeEnabled) return UnmSourceResult.Unavailable(SourceFailure.UNAVAILABLE)
    val baseUrl = unmBridgeBaseUrl
    if (baseUrl.isBlank()) return UnmSourceResult.Unavailable(SourceFailure.UNAVAILABLE)
    if (!isPlaybackNetworkUsable(application)) return UnmSourceResult.Unavailable(SourceFailure.NETWORK)
    val neteaseId = song.id.takeIf { it > 0L }?.toString()
        ?: return UnmSourceResult.Unavailable(SourceFailure.NO_MATCH)
    val budget = sourceStageBudgetMs(3_000, 5_000)
    if (budget <= 0) return UnmSourceResult.Unavailable(SourceFailure.TIMEOUT)
    val session = sourceResolutionSession(UNM_PROVIDER)
    val outcome = try {
        withTimeoutOrNull(budget) {
            unmBridgeClient.match(
                bridgeBaseUrl = baseUrl,
                neteaseId = neteaseId,
                expectedDurationMs = song.durationMs.takeIf { it > 0L }
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    if (!unmBridgeEnabled || unmBridgeBaseUrl != baseUrl) {
        return UnmSourceResult.Unavailable(SourceFailure.UNAVAILABLE)
    }
    if (session != sourceResolutionSession(UNM_PROVIDER)) {
        return UnmSourceResult.Unavailable(SourceFailure.UNAVAILABLE)
    }
    val audio = when (outcome) {
        is UnmBridgeOutcome.Matched -> outcome.audio
        is UnmBridgeOutcome.Failed -> {
            NPLogger.d("NERI-PlayerManager", "Match bridge unavailable: reason=${outcome.reason.name}")
            return UnmSourceResult.Unavailable(
                when (outcome.reason) {
                    UnmBridgeFailure.UNREACHABLE -> SourceFailure.NETWORK
                    UnmBridgeFailure.NOT_FOUND -> SourceFailure.NO_MATCH
                    UnmBridgeFailure.UNCONFIGURED, UnmBridgeFailure.REJECTED -> SourceFailure.UNAVAILABLE
                }
            )
        }
        null -> {
            NPLogger.d("NERI-PlayerManager", "Match bridge timed out within ${budget}ms")
            return UnmSourceResult.Unavailable(SourceFailure.TIMEOUT)
        }
    }
    sideEffects.updateDuration { maybeUpdateSongDuration(song, audio.durationMs) }
    return UnmSourceResult.Ready(
        SongUrlResult.Success(
            url = streamOnlyPlaybackUrl(audio.url),
            durationMs = audio.durationMs,
            mimeType = audio.mimeType,
            expectedContentLength = audio.sizeBytes,
            noticeMessage = getLocalizedString(CoreCommonR.string.unimusic_sources_unm_notice),
            audioInfo = PlaybackAudioInfo(
                source = PlaybackAudioSource.NETEASE,
                qualityLabel = "${audio.bitrateKbps} kbps",
                bitrateKbps = audio.bitrateKbps,
                mimeType = audio.mimeType,
                codecLabel = deriveCodecLabel(audio.mimeType)
            ),
            cacheKeyOverride = "unm-${audio.source}-$neteaseId-${audio.bitrateKbps}"
        )
    )
}
