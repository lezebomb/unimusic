package moe.ouom.neriplayer.ui.viewmodel.tab

import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.SourceCheckResult
import moe.ouom.neriplayer.data.model.unified.SourceCheckStatus
import moe.ouom.neriplayer.common.search.SearchTextMatcher
import moe.ouom.neriplayer.common.search.normalizeFederatedQuery
import moe.ouom.neriplayer.core.player.policy.source.RecordingMatchPolicy
import moe.ouom.neriplayer.core.player.policy.source.RecordingMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.search.searchValues

/** One query/context only. Cache bounds do not impose a search-result limit. */
internal class ExploreSearchRankingSession(query: String) {
    private val normalized = normalizeFederatedQuery(query)
    private val textQuery = SearchTextMatcher.query(normalized)
    private val requestedMetadata = RecordingMetadata(normalized, "", 0)
    private val requestedVersions = RecordingMatchPolicy.versions(requestedMetadata)
    private data class Score(val text: Int?, val versionPenalty: Int)
    private data class Cached(val item: ExploreSearchResult, val score: Score)
    private val scores = LinkedHashMap<String, Cached>(64, 0.75f, true)
    @Volatile var artistIntent: moe.ouom.neriplayer.ui.viewmodel.tab.search.SearchArtistIntent? = null

    /** Called on the UI state owner. Publish immediately after return, without another suspension. */
    suspend fun rankWithCurrentChecks(items: List<ExploreSearchResult>,
        checks: () -> Map<SongSourceKey, SourceCheckResult>, isCurrent: () -> Boolean,
        worker: CoroutineDispatcher = Dispatchers.Default): List<ExploreSearchResult>? {
        while (isCurrent()) {
            currentCoroutineContext().ensureActive()
            val captured = checks()
            val capturedIntent = artistIntent
            val ranked = withContext(worker) {
                val context = currentCoroutineContext()
                rank(items, captured) { context.ensureActive() }
            }
            if (!isCurrent()) return null
            // An address probe may finish while metadata is ranked on the worker. Never publish its old evidence.
            if (checks() === captured && artistIntent === capturedIntent) return ranked
        }
        return null
    }

    @Synchronized
    fun rank(items: List<ExploreSearchResult>, checks: Map<moe.ouom.neriplayer.data.model.unified.SongSourceKey,
        moe.ouom.neriplayer.data.model.unified.SourceCheckResult> = emptyMap(),
        checkActive: () -> Unit = {}): List<ExploreSearchResult> {
        checkActive()
        val intent = artistIntent
        val displayItems = if (intent == null) items else (intent.cards() + items).distinctBy { it.stableKey }
        if (normalized.isBlank() || displayItems.size <= 1) return displayItems
        val now = System.currentTimeMillis()
        data class Ranked(val item: ExploreSearchResult, val index: Int, val score: Score, val availability: Int, val intentTier: Int, val promotion: Int)
        val ranked = displayItems.mapIndexed { index, item ->
            if (index and 127 == 0) checkActive()
            val cached = scores[item.stableKey]
            val score = if (cached != null && cached.item == item) cached.score else score(item).also { value ->
                scores[item.stableKey] = Cached(item, value)
                while (scores.size > 2048) scores.remove(scores.keys.first())
            }
            val check = searchAvailabilityCandidate(item)?.let { checks[it.key] }
                ?.takeIf { now - it.checkedAtMillis in 0..120_000 }
            val availability = when (check?.status) {
                SourceCheckStatus.ADDRESS_RESOLVED -> 0
                null -> 1
                else -> 2
            }
            Ranked(item, index, score, availability, intent?.tier(item) ?: 0,
                moe.ouom.neriplayer.ui.viewmodel.tab.search.searchPromotionPenalty(normalized, item))
        }
        val comparator = compareBy<Ranked> { it.promotion }
            .thenBy { it.intentTier }
            // Artist/work relevance is already established: a literal work label must not lift Live above studio.
            .thenBy { if (intent != null && it.intentTier == 1) it.score.versionPenalty else 0 }
            .thenBy { it.score.text == null }
            // A bounded version preference must not outweigh a much better title/artist match.
            .thenBy { ((it.score.text?.toLong() ?: Int.MAX_VALUE.toLong()) + minOf(2, it.score.versionPenalty) * 8) / 16 }
            .thenBy { it.score.versionPenalty }
            .thenBy { it.availability }
            .thenBy { (it.score.text?.toLong() ?: Int.MAX_VALUE.toLong()) + minOf(2, it.score.versionPenalty) * 8 }
            .thenBy { it.score.text ?: Int.MAX_VALUE }
            .thenBy { it.item is ExploreSearchResult.Catalog }
            .thenBy { it.index }
        var comparisons = 0
        return ranked.sortedWith { first, second ->
            if (comparisons++ and 127 == 0) checkActive()
            comparator.compare(first, second)
        }.mapIndexed { index, value ->
            if (index and 127 == 0) checkActive()
            value.item
        }
    }

    private fun score(item: ExploreSearchResult): Score {
        val (metadata, values) = when (item) {
            is ExploreSearchResult.Song -> with(item.song) {
                RecordingMetadata(name, artist, durationMs, versionContext = album) to searchValues()
            }
            is ExploreSearchResult.Catalog -> with(item.track.metadata) {
                RecordingMetadata(title, artist, durationMs, version, album) to catalogSearchValues(item.track)
            }
            else -> return Score(null, 0)
        }
        val versions = RecordingMatchPolicy.versions(metadata)
        val difference = (versions - requestedVersions).size + (requestedVersions - versions).size
        // Same enum sets can still hide added labels or synthetic vocals; retain the shared policy's evidence.
        val penalty = difference.takeIf { it > 0 } ?: if (RecordingMatchPolicy.hasVersionConflict(requestedMetadata, metadata)) 1 else 0
        return Score(textQuery.score(values), penalty)
    }
}

/** Display ranking only; never merges or certifies recording identities. */
internal fun rankExploreSongSearchResults(query: String, songs: List<SongItem>): List<SongItem> =
    searchSongItems(ExploreSearchRankingSession(query).rank(songs.map(ExploreSearchResult::Song)))

/** Preserve field boundaries even when provider data contains separators or missing components. */
internal fun songSearchResultKey(song: SongItem): String = listOf("song", song.channelId.orEmpty(), song.audioId.orEmpty(),
    song.subAudioId.orEmpty(), song.mediaUri.orEmpty(), song.id.toString()).joinToString("|") { "${it.length}:$it" }

/** Only explicit provider identities can receive a result's transient audio check. No legacy-ID guessing. */
internal fun searchAvailabilityCandidate(item: ExploreSearchResult): moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate? = when (item) {
    is ExploreSearchResult.Catalog -> moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate(item.track.source, item.track.metadata)
    is ExploreSearchResult.Song -> if (item.song.channelId == "netease")
        moe.ouom.neriplayer.data.unified.sourceCandidateForSong(item.song) else null
    else -> null
}
