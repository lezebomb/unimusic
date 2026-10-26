package moe.ouom.neriplayer.core.player.resolver.netease

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.policy.source.*
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.url.buildBiliPlaybackAudioInfo
import moe.ouom.neriplayer.core.player.url.buildBiliRepresentationIdentity
import moe.ouom.neriplayer.core.player.url.inferBiliQualityKey
import moe.ouom.neriplayer.core.player.url.sourceResolutionSession
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoItem
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.bilibili.playback.PlayOptions
import moe.ouom.neriplayer.core.player.quality.effectiveBiliQuality
import moe.ouom.neriplayer.platform.bilibili.playback.quality.selectStreamByPreference
import moe.ouom.neriplayer.common.logging.NPLogger
import kotlin.math.absoluteValue
import moe.ouom.neriplayer.data.unified.SourceMatchBackend
import moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.SourceMatchDecision
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata

private const val NETEASE_AUTO_SOURCE_SEARCH_LIMIT = 6
private val autoSourceCacheKeyUnsafeRegex = Regex("[^A-Za-z0-9_.-]+")
private val autoSourceNonTextRegex = Regex("[^\\p{L}\\p{N}]+")
private val autoSourceWhitespaceRegex = Regex("\\s+")

internal suspend fun PlayerManager.tryResolveNeteaseAutoBiliSource(
    song: SongItem,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult? {
    if (!neteaseAutoSourceSwitchEnabled) return null
    val queries = buildNeteaseAutoSourceQueries(song)
    if (queries.isEmpty()) return null
    val candidates = flow {
        suspend fun offer(candidate: SearchVideoItem) {
            emit(PlaybackSourceCandidate(
                id = NeteaseAutoSourceState.candidateId(candidate),
                // Preserve the existing explicit opt-in. Stream access is checked by biliRepo;
                // neither title similarity nor a public URL creates a new platform entitlement.
                audioAllowed = neteaseAutoSourceSwitchEnabled,
                resolve = {
                    val result = resolveStrictBiliSource(song, candidate, sideEffects)
                    if (result is SourceAttempt.Ready) NeteaseAutoSourceState.selected(song, candidate)
                    result
                }
            ))
        }
        NeteaseAutoSourceState.previous(song)?.let { offer(it) }
        for (query in queries) {
            val expected = song.recordingMetadata()
            val found = fetchBiliAutoSourceCandidates(song, query)
            RecordingDiscoveryRanker.rank(expected, found.map { candidate ->
                RecordingDiscoveryCandidate(NeteaseAutoSourceState.candidateId(candidate),
                    biliRecordingMetadata(expected, candidate.titlePlain, candidate.titlePlain,
                        candidate.author, candidate.durationSec * 1000L),
                    candidate, scoreNeteaseAutoBiliCandidate(song, candidate))
            })
                .take(NETEASE_AUTO_SOURCE_SEARCH_LIMIT).forEach { offer(it) }
        }
    }
    val result = PlaybackSourceResolver(NeteaseAutoSourceState.cooldown)
        .resolve(NeteaseAutoSourceState.key(song), candidates)
    NPLogger.d("NERI-PlayerManager", "Bili alternate resolution: selected=${result.candidateId != null}, failures=${result.failures.distinct()}")
    return result.value
}

private fun buildNeteaseAutoSourceQueries(song: SongItem): List<String> {
    val title = (song.originalName ?: song.name).trim()
    val artist = (song.originalArtist ?: song.artist).trim()
    if (title.isBlank() || artist.isBlank()) return emptyList()
    return listOf("$title $artist", "$artist $title").map(::normalizeAutoSourceQuery).distinct()
}

private suspend fun PlayerManager.fetchBiliAutoSourceCandidates(song: SongItem, query: String): List<SearchVideoItem> {
    val durationFilter = durationFilterForBiliSearch(song.durationMs)
    return try {
        val first = biliClient.searchVideos(keyword = query, page = 1, duration = durationFilter).items
        if (first.isNotEmpty() || durationFilter == 0) first
        else biliClient.searchVideos(keyword = query, page = 1, duration = 0).items
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}

internal suspend fun PlayerManager.resolveStrictBiliSource(
    song: SongItem, candidate: SearchVideoItem, sideEffects: RefreshResolverSideEffects, selectedCid: Long? = null
): SourceAttempt<SongUrlResult.Success> {
    try {
        val session = sourceResolutionSession("bilibili")
        val info = if (candidate.bvid.isNotBlank()) biliClient.getVideoBasicInfoByBvid(candidate.bvid)
            else biliClient.getVideoBasicInfoByAvid(candidate.aid)
        if ((candidate.aid > 0 && info.aid != candidate.aid) ||
            (candidate.bvid.isNotBlank() && info.bvid != candidate.bvid)) return SourceAttempt.Rejected(SourceFailure.NO_MATCH)
        val selection = selectStrictBiliPage(song, info, candidate.titlePlain, selectedCid)
        val page = selection.page ?: return SourceAttempt.Rejected(selection.rejection ?: SourceFailure.NO_MATCH)
        val matchBackend = SourceMatchBackend(application)
        val sourceKey = SongSourceKey("bilibili", info.aid.toString(), page.cid.toString())
        if (matchBackend.isRejected(song, sourceKey)) return SourceAttempt.Rejected(SourceFailure.NO_MATCH)
        val playInfo = biliClient.getPlayInfoByBvid(info.bvid, page.cid, PlayOptions(tryLook = 0))
        biliAutoSourceAccessFailure(playInfo, song.durationMs)?.let { return SourceAttempt.Rejected(it) }
        val available = with(biliClient) { playInfo.toAudioStreamInfos() }
        val selected = selectStreamByPreference(available, effectiveBiliQuality())
        val stream = selected?.takeIf { it.url.isNotBlank() }
            ?: return SourceAttempt.Rejected(SourceFailure.NO_PERMISSION)
        val duration = page.durationSec.takeIf { it > 0 }?.times(1000L) ?: info.durationSec * 1000L
        val expected = song.recordingMetadata()
        val title = if (info.pages.size == 1 && page.part.matches(Regex("(?i)(?:p?1|正片|视频|音频|默认|video|audio)"))) info.title else page.part
        val metadata = biliRecordingMetadata(expected, title, "${info.title} ${page.part}", info.ownerName, duration)
        val observed = matchBackend.observe(song, RecordingSourceCandidate(sourceKey,
            UnifiedSongMetadata(metadata.title, metadata.artist, metadata.versionContext, metadata.durationMs)))
        if (observed.decision == SourceMatchDecision.REJECTED) return SourceAttempt.Rejected(SourceFailure.NO_MATCH)
        if (session != sourceResolutionSession("bilibili")) return SourceAttempt.Rejected(SourceFailure.AUTH_REQUIRED)
        sideEffects.updateDuration { maybeUpdateSongDuration(song, duration) }
        return SourceAttempt.Ready(SongUrlResult.Success(
            url = stream.url, candidateUrls = stream.candidateUrls, durationMs = duration,
            mimeType = stream.mimeType,
            audioInfo = buildBiliPlaybackAudioInfo(stream, available) { getLocalizedString(it) },
            representationIdentity = buildBiliRepresentationIdentity(stream),
            cacheKeyOverride = buildNeteaseAutoBiliCacheKey(info.bvid, page.cid, stream)
        ))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: java.io.IOException) {
        return SourceAttempt.Rejected(SourceFailure.NETWORK)
    } catch (_: Exception) {
        return SourceAttempt.Rejected(SourceFailure.UNAVAILABLE)
    }
}

internal fun buildNeteaseAutoBiliCacheKey(
    bvid: String,
    cid: Long,
    selectedStream: BiliAudioStreamInfo
): String {
    val bvidPart = sanitizeNeteaseAutoBiliCacheKeyPart(bvid)
    val streamPart = selectedStream.id
        ?.let { "id-$it" }
        ?: selectedStream.qualityTag
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?.let { "tag-${sanitizeNeteaseAutoBiliCacheKeyPart(it)}" }
        ?: "quality-${inferBiliQualityKey(selectedStream)}-${selectedStream.bitrateKbps}"
    return "bili-auto-$bvidPart-$cid-$streamPart"
}

private fun sanitizeNeteaseAutoBiliCacheKeyPart(value: String): String {
    return autoSourceCacheKeyUnsafeRegex
        .replace(value.trim(), "_")
        .trim('_')
        .ifBlank { "unknown" }
}

internal fun scoreNeteaseAutoBiliCandidate(
    song: SongItem,
    candidate: SearchVideoItem
): Int {
    return scoreNeteaseAutoBiliText(
        song = song,
        title = candidate.titlePlain,
        author = candidate.author,
        durationSec = candidate.durationSec
    )
}

internal fun scoreNeteaseAutoBiliText(
    song: SongItem,
    title: String,
    author: String,
    durationSec: Int
): Int {
    val songTitle = song.originalName ?: song.name
    val songArtist = song.originalArtist ?: song.artist
    val normalizedTitle = normalizeAutoSourceText(title)
    val normalizedAuthor = normalizeAutoSourceText(author)
    val titleHit = containsAutoSourceText(normalizedTitle, songTitle)
    val artistHit = containsAutoSourceText(normalizedTitle, songArtist) ||
        containsAutoSourceText(normalizedAuthor, songArtist)

    var score = 0
    if (titleHit) {
        score += 55
    } else {
        score += tokenOverlapScore(normalizedTitle, songTitle)
    }
    if (songArtist.isNotBlank() && artistHit) {
        score += 25
    }
    score += durationSimilarityScore(song.durationMs, durationSec)
    return score
}

private fun durationFilterForBiliSearch(durationMs: Long): Int {
    val durationSec = (durationMs / 1000L).toInt()
    return when {
        durationSec <= 0 -> 0
        durationSec < 10 * 60 -> 1
        durationSec < 30 * 60 -> 2
        durationSec < 60 * 60 -> 3
        else -> 4
    }
}

private fun durationSimilarityScore(originalDurationMs: Long, candidateDurationSec: Int): Int {
    if (originalDurationMs <= 0L || candidateDurationSec <= 0) return 0

    val candidateDurationMs = candidateDurationSec * 1000L
    val diffMs = (candidateDurationMs - originalDurationMs).absoluteValue
    return when {
        diffMs <= 8_000L -> 30
        diffMs <= 20_000L -> 22
        diffMs <= 45_000L -> 12
        candidateDurationMs > originalDurationMs * 2 -> -15
        else -> 0
    }
}

private fun containsAutoSourceText(normalizedText: String, rawNeedle: String): Boolean {
    val needle = compactAutoSourceText(rawNeedle)
    if (needle.length < 2) return false
    return normalizedText.replace(" ", "").contains(needle)
}

private fun tokenOverlapScore(normalizedText: String, rawNeedle: String): Int {
    val tokens = normalizeAutoSourceText(rawNeedle)
        .split(' ')
        .filter { it.length >= 2 }
    if (tokens.isEmpty()) return 0

    val hits = tokens.count { normalizedText.contains(it) }
    return when {
        hits == tokens.size -> 35
        hits > 0 -> 18
        else -> 0
    }
}

private fun normalizeAutoSourceQuery(value: String): String {
    return autoSourceWhitespaceRegex
        .replace(value.trim(), " ")
        .trim()
}

private fun normalizeAutoSourceText(value: String): String {
    return autoSourceWhitespaceRegex
        .replace(autoSourceNonTextRegex.replace(value.lowercase(), " "), " ")
        .trim()
}

private fun compactAutoSourceText(value: String): String {
    return normalizeAutoSourceText(value).replace(" ", "")
}
