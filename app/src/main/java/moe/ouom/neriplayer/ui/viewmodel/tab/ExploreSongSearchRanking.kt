package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.common.search.SearchTextMatcher
import moe.ouom.neriplayer.common.search.normalizeFederatedQuery
import moe.ouom.neriplayer.core.player.policy.source.RecordingMatchPolicy
import moe.ouom.neriplayer.core.player.policy.source.RecordingMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.search.searchValues

/** Ranking is for discovery, never evidence that two results are the same recording. */
internal fun rankExploreSongSearchResults(query: String, songs: List<SongItem>): List<SongItem> {
    val normalized = normalizeFederatedQuery(query)
    if (normalized.isBlank() || songs.size <= 1) return songs
    val requestedVersions = RecordingMatchPolicy.versions(RecordingMetadata(normalized, "", 0))
    data class Ranked(val song: SongItem, val index: Int, val score: Int?, val versionPenalty: Int)
    return songs.mapIndexed { index, song ->
        val versions = RecordingMatchPolicy.versions(RecordingMetadata(song.name, song.artist, song.durationMs))
        Ranked(song, index, SearchTextMatcher.score(normalized, song.searchValues()),
            (versions - requestedVersions).size + (requestedVersions - versions).size)
    }.sortedWith(compareBy<Ranked> { it.score == null }
        .thenBy { it.versionPenalty }.thenBy { it.score ?: Int.MAX_VALUE }.thenBy { it.index })
        .map { it.song }
}
