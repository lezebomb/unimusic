package moe.ouom.neriplayer.ui.viewmodel.tab

import java.util.LinkedHashMap
import moe.ouom.neriplayer.common.search.SearchTextMatcher
import moe.ouom.neriplayer.common.search.normalizeFederatedQuery
import moe.ouom.neriplayer.core.player.policy.source.RecordingMatchPolicy
import moe.ouom.neriplayer.core.player.policy.source.RecordingMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.search.searchValues

/** One query/context only. Cache bounds do not impose a search-result limit. */
internal class ExploreSearchRankingSession(query: String) {
    private val normalized = normalizeFederatedQuery(query)
    private val requestedVersions = RecordingMatchPolicy.versions(RecordingMetadata(normalized, "", 0))
    private data class Score(val text: Int?, val versionPenalty: Int)
    private data class Cached(val item: ExploreSearchResult, val score: Score)
    private val scores = LinkedHashMap<String, Cached>(64, 0.75f, true)

    @Synchronized
    fun rank(items: List<ExploreSearchResult>): List<ExploreSearchResult> {
        if (normalized.isBlank() || items.size <= 1) return items
        data class Ranked(val item: ExploreSearchResult, val index: Int, val score: Score)
        return items.mapIndexed { index, item ->
            val cached = scores[item.stableKey]
            val score = if (cached != null && cached.item == item) cached.score else score(item).also { value ->
                scores[item.stableKey] = Cached(item, value)
                while (scores.size > 2048) scores.remove(scores.keys.first())
            }
            Ranked(item, index, score)
        }.sortedWith(compareBy<Ranked> { it.score.text == null }
            // A bounded version preference must not outweigh a much better title/artist match.
            .thenBy { (it.score.text?.toLong() ?: Int.MAX_VALUE.toLong()) + minOf(2, it.score.versionPenalty) * 8 }
            .thenBy { it.score.text ?: Int.MAX_VALUE }
            .thenBy { it.item is ExploreSearchResult.Catalog }
            .thenBy { it.index })
            .map { it.item }
    }

    private fun score(item: ExploreSearchResult): Score {
        val (title, values) = when (item) {
            is ExploreSearchResult.Song -> item.song.name to item.song.searchValues()
            is ExploreSearchResult.Catalog -> item.track.metadata.title to catalogSearchValues(item.track)
            else -> return Score(null, 0)
        }
        val versions = RecordingMatchPolicy.versions(RecordingMetadata(title, "", 0))
        return Score(SearchTextMatcher.score(normalized, values),
            (versions - requestedVersions).size + (requestedVersions - versions).size)
    }
}

/** Display ranking only; never merges or certifies recording identities. */
internal fun rankExploreSongSearchResults(query: String, songs: List<SongItem>): List<SongItem> =
    searchSongItems(ExploreSearchRankingSession(query).rank(songs.map(ExploreSearchResult::Song)))
