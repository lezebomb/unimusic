package moe.ouom.neriplayer.data.sync.unified

import moe.ouom.neriplayer.data.model.unified.sync.*

data class LocalIdentityObservation(
    val songId: String,
    val metadata: PortableSongMetadata,
    val sources: List<PortableSongIdentity>
)

data class UnifiedIdentityCaptureResult(
    val merged: UnifiedIdentityMergeResult,
    val changedSongs: Int,
    val suppressedDeletedSongs: Int,
    val conflictingLocalGroups: Int
)

internal data class IdentityCapturePlan(
    val result: UnifiedIdentityCaptureResult,
    val counter: Long,
    val baselines: Map<String, PortableSongMetadata>
)

/** One batch merge/write, with a local baseline so unchanged Room rows cannot undo remote edits. */
internal object UnifiedIdentityCapturePlanner {
    fun plan(dataset: UnifiedIdentityDataset, baselines: Map<String, PortableSongMetadata>, deviceId: String,
        counter: Long, observations: List<LocalIdentityObservation>): IdentityCapturePlan {
        require(observations.size <= UnifiedIdentityValidation.MAX_IDENTITIES) { "Too many observed songs" }
        require(observations.map { it.songId }.distinct().size == observations.size) { "Duplicate local song observation" }
        observations.forEach { observation ->
            require(observation.sources.isNotEmpty() && observation.sources.all { it.songId == observation.songId }) { "Invalid local observation sources" }
            UnifiedIdentityValidation.validateMetadata(observation.metadata)
        }
        val identities = (dataset.identities + observations.flatMap { it.sources }).distinct()
        val baseline = UnifiedIdentityMerger.merge(dataset.copy(identities = identities), UnifiedIdentityDataset())
        val states = baseline.songs.associateBy { it.songId }
        val nextBaselines = baselines.toMutableMap()
        val revisions = mutableListOf<UnifiedSongRevision>()
        var nextCounter = counter
        var suppressed = 0
        var conflicts = 0
        observations.groupBy { baseline.aliases.getValue(it.songId) }.toSortedMap().forEach { (canonical, entries) ->
            val current = states[canonical]
            val changes = entries.filter { entry ->
                val previous = baselines[entry.songId]
                if (previous == null) current == null else previous != entry.metadata
            }
            if (current?.deleted == true) {
                suppressed += entries.size
            } else if (current?.hasConflict == true || entries.any { entry ->
                val previous = baselines[entry.songId]
                current != null && current.metadata != entry.metadata &&
                    (previous == null || (previous != entry.metadata && current.metadata != previous))
            } || changes.map { it.metadata }.distinct().size > 1) {
                // A snapshot cannot infer which edits the user actually observed. Require explicit resolution.
                conflicts++
                return@forEach // Keep prior baselines so a subsequent capture still exposes this conflict.
            } else if (changes.isNotEmpty()) {
                val metadata = changes.first().metadata
                if (current == null || current.concurrentRevisions.any { it.metadata != metadata }) {
                    require(nextCounter < Long.MAX_VALUE - 1) { "Causal counter exhausted" }
                    val context = mutableMapOf<String, Long>()
                    current?.concurrentRevisions?.forEach { revision ->
                        (revision.context + (revision.dot.deviceId to revision.dot.counter)).forEach { (actor, count) ->
                            context[actor] = maxOf(context[actor] ?: 0L, count)
                        }
                    }
                    revisions += UnifiedSongRevision(changes.minOf { it.songId }, UnifiedSyncDot(deviceId, ++nextCounter),
                        context.toSortedMap(), metadata)
                }
            }
            entries.forEach { nextBaselines[it.songId] = it.metadata }
        }
        val merged = if (revisions.isEmpty()) baseline else UnifiedIdentityMerger.merge(baseline.dataset,
            UnifiedIdentityDataset(identities = identities, revisions = revisions))
        return IdentityCapturePlan(UnifiedIdentityCaptureResult(merged, revisions.size, suppressed, conflicts), nextCounter,
            nextBaselines.toSortedMap())
    }
}
