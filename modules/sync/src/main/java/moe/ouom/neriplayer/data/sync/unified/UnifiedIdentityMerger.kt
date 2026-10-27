package moe.ouom.neriplayer.data.sync.unified

import java.util.TreeMap
import moe.ouom.neriplayer.data.model.unified.sync.*

/** Alias decisions use exact public source keys, never title/artist similarity. */
data class UnifiedIdentityMergeResult(
    val dataset: UnifiedIdentityDataset,
    val aliases: Map<String, String>,
    val songs: List<UnifiedIdentityState>
)

data class UnifiedIdentityState(
    val songId: String,
    val deleted: Boolean,
    val metadata: PortableSongMetadata?,
    val concurrentRevisions: List<UnifiedSongRevision>
) {
    val hasConflict: Boolean get() = concurrentRevisions.map { it.metadata }.distinct().size > 1
}

/** Pure remove-wins causal register. It neither applies Room changes nor uploads anything. */
object UnifiedIdentityMerger {
    fun merge(left: UnifiedIdentityDataset, right: UnifiedIdentityDataset): UnifiedIdentityMergeResult {
        UnifiedIdentityValidation.validate(left)
        UnifiedIdentityValidation.validate(right)
        val identities = (left.identities + right.identities).distinct().sortedWith(
            compareBy({ it.songId }, { it.provider }, { it.resourceId }, { it.subResourceId }, { it.recordingContext }))
        val revisionsByDot = linkedMapOf<UnifiedSyncDot, UnifiedSongRevision>()
        (left.revisions + right.revisions).forEach { revision ->
            val previous = revisionsByDot.putIfAbsent(revision.dot, revision)
            require(previous == null || previous == revision) { "Conflicting causal dot" }
        }
        val combined = UnifiedIdentityDataset(identities = identities, revisions = revisionsByDot.values.toList())
        UnifiedIdentityValidation.validate(combined)
        val parents = identities.associate { it.songId to it.songId }.toMutableMap()
        fun root(id: String): String {
            var current = id
            while (parents.getValue(current) != current) current = parents.getValue(current)
            var cursor = id
            while (parents.getValue(cursor) != cursor) {
                val next = parents.getValue(cursor)
                parents[cursor] = current
                cursor = next
            }
            return current
        }
        val sources = hashMapOf<List<String>, String>()
        identities.forEach { identity ->
            val key = listOf(identity.provider, identity.resourceId, identity.subResourceId, identity.recordingContext)
            val previous = sources.putIfAbsent(key, identity.songId)
            if (previous != null) {
                val a = root(previous)
                val b = root(identity.songId)
                parents[maxOf(a, b)] = minOf(a, b)
            }
        }
        val aliases = parents.keys.sorted().associateWith(::root)
        val groups = combined.revisions.groupBy { aliases.getValue(it.songId) }
        val songs = groups.toSortedMap().map { (songId, revisions) ->
            validateCausality(revisions)
            // At most one live frontier candidate per actor, irrespective of history size.
            val candidates = revisions.groupBy { it.dot.deviceId }.values.map { entries -> entries.maxBy { it.dot.counter } }
            val frontier = candidates.filter { revision -> candidates.none { other -> other !== revision && observes(other, revision) } }
                .sortedWith(compareBy({ it.dot.deviceId }, { it.dot.counter }))
                .map { it.copy(context = it.context.toSortedMap()) }
            val deleted = frontier.any { it.metadata == null }
            UnifiedIdentityState(songId, deleted, if (deleted) null else frontier.first().metadata, frontier)
        }
        // Retain source proofs even for tombstoned IDs so old aliases cannot resurrect a recording.
        val normalized = combined.copy(revisions = songs.flatMap { it.concurrentRevisions })
        return UnifiedIdentityMergeResult(normalized, aliases, songs)
    }

    private fun observes(later: UnifiedSongRevision, earlier: UnifiedSongRevision): Boolean =
        (later.context[earlier.dot.deviceId] ?: 0L) >= earlier.dot.counter

    private fun validateCausality(revisions: List<UnifiedSongRevision>) {
        val byDevice = revisions.groupBy { it.dot.deviceId }.mapValues { (_, entries) ->
            TreeMap<Long, UnifiedSongRevision>().apply { entries.forEach { put(it.dot.counter, it) } }
        }
        byDevice.values.forEach { entries -> entries.values.zipWithNext().forEach { (earlier, later) ->
            require(observes(later, earlier)) { "Forked per-identity device history" }
        } }
        revisions.forEach { revision -> revision.context.forEach contextLoop@ { (device, counter) ->
            val observed = byDevice[device]?.floorEntry(counter)?.value ?: return@contextLoop
            require(observed.context.all { (actor, count) -> (revision.context[actor] ?: 0L) >= count }) {
                "Non-transitive causal context"
            }
        } }
    }
}
