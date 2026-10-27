package moe.ouom.neriplayer.core.player.resolver.netease

import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.data.identity.stableKey

import androidx.core.net.toUri
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.core.player.url.buildLocalPlaybackAudioInfo
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.stableKey
import kotlin.math.absoluteValue
import moe.ouom.neriplayer.core.player.policy.source.RecordingMatchPolicy
import moe.ouom.neriplayer.core.player.policy.source.RecordingMetadata
import moe.ouom.neriplayer.data.unified.SourceMatchBackend
import moe.ouom.neriplayer.data.unified.sourceCandidateForSong
import moe.ouom.neriplayer.data.model.unified.SourceMatchDecision
import moe.ouom.neriplayer.core.player.url.RememberedSourceState

internal const val NETEASE_LOCAL_FALLBACK_DURATION_TOLERANCE_MS = 8_000L

internal suspend fun PlayerManager.tryResolveNeteaseMatchedLocalSource(song: SongItem,
    explicitCandidate: SongItem? = null, automatic: Boolean = explicitCandidate == null): SongUrlResult? {
    if (automatic && !neteaseLocalSourceFallbackEnabled) return null

    val localSongs = localRepo.playlists.value.flatMap { it.songs }
    val candidates = selectNeteaseLocalFallbackCandidates(song, explicitCandidate?.let(::listOf) ?: localSongs)
    if (candidates.isEmpty()) return null

    for (candidate in candidates) {
        val descriptor = sourceCandidateForSong(candidate) ?: continue
        val matchBackend = SourceMatchBackend(application)
        val stamp = matchBackend.decisionStamp(song, descriptor.key)
        if (stamp.decision == SourceMatchDecision.REJECTED) continue
        val localMediaUri = PlayerDependencies.downloads.resolvePermittedLocalPlaybackUri(
            context = application,
            song = candidate,
            rawLocalReference = localMediaSource(candidate)
        ) ?: continue
        if (!isReadableLocalMediaUri(localMediaUri)) continue
        if (automatic && !neteaseLocalSourceFallbackEnabled) return null
        if (stamp != matchBackend.decisionStamp(song, descriptor.key)) continue
        val observed = if (automatic) matchBackend.rememberAutomatic(song, descriptor, stamp) ?: continue
            else matchBackend.observe(song, descriptor)
        if (observed.decision == SourceMatchDecision.REJECTED ||
            (automatic && RememberedSourceState.cooldown.cooling(song.stableKey(), observed.id))) continue
        val playableUrl = toPlayableLocalUrl(localMediaUri) ?: localMediaUri
        NPLogger.w(
            "NERI-PlayerManager",
            "Netease source unavailable, fallback to matched local audio: " +
                "song=${song.name}, matchedSongId=${candidate.matchedSongId}, " +
                "songDurationMs=${song.durationMs}, localDurationMs=${candidate.durationMs}"
        )
        val playableAudioInfo = runCatching { playableUrl.toUri() }.getOrNull()
            ?.let { buildLocalPlaybackAudioInfo(it, application) }
            ?: PlaybackAudioInfo(source = PlaybackAudioSource.LOCAL)
        if (automatic && !neteaseLocalSourceFallbackEnabled) return null
        if (stamp != matchBackend.decisionStamp(song, descriptor.key)) continue
        return SongUrlResult.Success(
            url = playableUrl,
            cacheKeyOverride = if (automatic) "match-${observed.id}-local" else null,
            audioInfo = playableAudioInfo.copy(isNeteaseLocalFallback = true),
            isNeteaseLocalFallback = true
        )
    }

    NPLogger.w(
        "NERI-PlayerManager",
        "Matched local audio not readable, skip local fallback: song=${song.name}"
    )
    return null
}

internal fun selectNeteaseLocalFallbackCandidates(
    song: SongItem,
    localSongs: List<SongItem>
): List<SongItem> {
    val localCandidates = localSongs.filter { candidate ->
        LocalSongSupport.isLocalSong(candidate)
    }
    if (localCandidates.isEmpty()) return emptyList()

    val targetId = song.id.toString()
    val exact = localCandidates.filter { candidate ->
        candidate.matchedLyricSource == MusicPlatform.CLOUD_MUSIC &&
            candidate.matchedSongId == targetId &&
            matchesNeteaseLocalFallbackMetadata(song, candidate)
    }
    val exactKeys = exact.mapTo(mutableSetOf()) { it.stableKey() }
    val rest = localCandidates.filterNot { it.stableKey() in exactKeys }
    val fuzzy = rest.filter { candidate ->
        matchesNeteaseLocalFallbackMetadata(song, candidate)
    }
    return (sortByDurationCloseness(song, exact) + sortByDurationCloseness(song, fuzzy))
        .distinctBy { it.stableKey() }
}

internal fun matchesNeteaseLocalFallbackMetadata(song: SongItem, candidate: SongItem): Boolean {
    return RecordingMatchPolicy.assess(song.recordingMetadata(), RecordingMetadata(
        candidate.displayName(), candidate.displayArtist(), candidate.durationMs,
        versionContext = "${candidate.name} ${candidate.album}"
    ), NETEASE_LOCAL_FALLBACK_DURATION_TOLERANCE_MS).accepted
}

private fun sortByDurationCloseness(song: SongItem, candidates: List<SongItem>): List<SongItem> {
    if (candidates.size <= 1) return candidates
    return candidates.sortedBy { candidate ->
        if (song.durationMs > 0L && candidate.durationMs > 0L) {
            (candidate.durationMs - song.durationMs).absoluteValue
        } else {
            Long.MAX_VALUE
        }
    }
}
