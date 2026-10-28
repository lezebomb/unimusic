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
    /** Equal recording evidence cannot identify which upload is genuine. Keep it for manual choice. */
    fun <T> unambiguous(expected: RecordingMetadata, candidates: List<RecordingDiscoveryCandidate<T>>): List<T> {
        val qualified = candidates.distinctBy { it.id }.filter { RecordingMatchPolicy.assess(expected, it.metadata).accepted }
        return if (qualified.size == 1) listOf(qualified.single().value) else emptyList()
    }

    fun <T> rank(expected: RecordingMetadata, candidates: List<RecordingDiscoveryCandidate<T>>): List<T> {
        val expectedVersions = RecordingMatchPolicy.versions(expected)
        val title = RecordingMatchPolicy.canonicalTitle(expected.title)
        val artist = RecordingMatchPolicy.canonicalArtist(expected.artist)
        return candidates.distinctBy { it.id }.mapNotNull { candidate ->
            if ((RecordingMatchPolicy.versions(candidate.metadata) - expectedVersions).isNotEmpty()) return@mapNotNull null
            Ranked(candidate, RecordingMatchPolicy.assess(expected, candidate.metadata).accepted,
                RecordingMatchPolicy.canonicalTitle(candidate.metadata.title) == title,
                artist.isNotEmpty() && RecordingMatchPolicy.canonicalArtist(candidate.metadata.artist) == artist,
                if (expected.durationMs > 0 && candidate.metadata.durationMs > 0)
                    abs(expected.durationMs - candidate.metadata.durationMs) else Long.MAX_VALUE)
        }.sortedWith(compareByDescending<Ranked<T>> { it.accepted }
            .thenByDescending { it.exactTitle }
            .thenByDescending { it.exactArtist }
            .thenBy { it.durationDifference }
            .thenByDescending { it.candidate.textRelevance })
            .map { it.candidate.value }
    }
    private data class Ranked<T>(val candidate: RecordingDiscoveryCandidate<T>, val accepted: Boolean,
        val exactTitle: Boolean, val exactArtist: Boolean, val durationDifference: Long)

}
