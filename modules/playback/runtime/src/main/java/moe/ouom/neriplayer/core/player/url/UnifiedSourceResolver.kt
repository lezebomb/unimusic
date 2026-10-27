package moe.ouom.neriplayer.core.player.url

import android.os.SystemClock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.policy.source.withSourceResolutionBudget
import moe.ouom.neriplayer.core.player.policy.source.primarySourceResolutionBudgetMs
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.runtime.refresh.YouTubePlaybackRecoveryStrategy
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.unified.UnifiedPlaybackBackend

/** Existing callers keep their original queue SongItem; only exact source links establish the UUID. */
internal suspend fun PlayerManager.resolveSongUrl(
    song: SongItem,
    forceRefresh: Boolean = false,
    youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects(),
    allowGenericPrefetchCache: Boolean = true,
    playbackRequestTokenOverride: Long? = null,
    shouldApplyCacheMutation: () -> Boolean = { true }
): SongUrlResult {
    val result = withSourceResolutionBudget(primarySourceResolutionBudgetMs(isYouTubeMusicTrack(song))) {
        val target = if (song.streamUrl.isNullOrBlank()) {
            try { UnifiedPlaybackBackend(application).identify(song) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // A temporarily unavailable projection must not disable the established player path.
                NPLogger.w("NERI-PlayerManager", "Unified identity unavailable: type=${error.javaClass.simpleName}")
                null
            }
        } else null
        resolveSongUrlFromLegacySource(song, forceRefresh, youtubeRecoveryStrategy, sideEffects,
            allowGenericPrefetchCache, playbackRequestTokenOverride, shouldApplyCacheMutation, target?.songId)
    }
    if (result != null) return result
    if (shouldApplyCacheMutation()) emitSourceResolutionTimeout(sideEffects)
    return SongUrlResult.Failure
}

/** UUID entry for unified-library consumers. Unknown/catalog-only IDs fail without guessing a URL. */
internal suspend fun PlayerManager.resolveUnifiedSongUrl(
    unifiedSongId: String,
    forceRefresh: Boolean = false,
    sideEffects: RefreshResolverSideEffects = RefreshResolverSideEffects()
): SongUrlResult {
    val started = SystemClock.elapsedRealtime()
    val backend = UnifiedPlaybackBackend(application)
    val target = withTimeoutOrNull(8_000) {
        try { backend.load(unifiedSongId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            NPLogger.w("NERI-PlayerManager", "Unified recording unavailable: type=${error.javaClass.simpleName}")
            null
        }
    } ?: return SongUrlResult.Failure
    val remaining = primarySourceResolutionBudgetMs(isYouTubeMusicTrack(target.song)) -
        (SystemClock.elapsedRealtime() - started)
    if (remaining <= 0) {
        emitSourceResolutionTimeout(sideEffects)
        return SongUrlResult.Failure
    }
    val result = withSourceResolutionBudget(remaining) {
        resolveSongUrlFromLegacySource(target.song, forceRefresh = forceRefresh,
            sideEffects = sideEffects, unifiedSongId = target.songId,
            shouldApplyCacheMutation = { backend.isCurrent(target) })
    }
    if (!backend.isCurrent(target)) return SongUrlResult.Failure
    if (result != null) return result
    emitSourceResolutionTimeout(sideEffects)
    return SongUrlResult.Failure
}

private fun PlayerManager.emitSourceResolutionTimeout(sideEffects: RefreshResolverSideEffects) {
    NPLogger.w("NERI-PlayerManager", "Source resolution budget exhausted")
    sideEffects.emitError {
        postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.unimusic_sources_resolution_timeout)))
    }
}
