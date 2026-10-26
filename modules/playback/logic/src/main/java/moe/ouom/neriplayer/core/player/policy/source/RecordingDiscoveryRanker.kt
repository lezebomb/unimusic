package moe.ouom.neriplayer.core.player.policy.source

import kotlin.math.abs

data class RecordingDiscoveryCandidate<T>(
    val id: String,
    val metadata: RecordingMetadata,
    val value: T,
    val textRelevance: Int = 0
)

/** Orders metadata discovery only. Actual page/stream access checks must still run before playback. */
object RecordingDiscoveryRanker {
    fun <T> rank(expected: RecordingMetadata, candidates: List<RecordingDiscoveryCandidate<T>>): List<T> {
        val expectedVersions = RecordingMatchPolicy.versions(expected)
        val title = RecordingMatchPolicy.canonicalTitle(expected.title)
        val artist = RecordingMatchPolicy.canonicalText(expected.artist)
        return candidates.distinctBy { it.id }
            .filter { (RecordingMatchPolicy.versions(it.metadata) - expectedVersions).isEmpty() }
            .sortedWith(compareByDescending<RecordingDiscoveryCandidate<T>> {
                RecordingMatchPolicy.assess(expected, it.metadata).accepted
            }.thenByDescending { RecordingMatchPolicy.canonicalTitle(it.metadata.title) == title }
                .thenByDescending { RecordingMatchPolicy.canonicalText(it.metadata.artist) == artist && artist.isNotEmpty() }
                .thenBy {
                    if (expected.durationMs > 0 && it.metadata.durationMs > 0) abs(expected.durationMs - it.metadata.durationMs)
                    else Long.MAX_VALUE
                }.thenByDescending { it.textRelevance })
            .map { it.value }
    }
}
