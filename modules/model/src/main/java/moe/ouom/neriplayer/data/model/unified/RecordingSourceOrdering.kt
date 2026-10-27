package moe.ouom.neriplayer.data.model.unified

/** Availability never overrides a wrong recording or an explicit rejection. Observations expire. */
fun orderRecordingSources(expected: RecordingMetadata, candidates: List<RecordingSourceCandidate>,
    decisions: Map<SongSourceKey, SourceMatchDecision>, checks: Map<SongSourceKey, SourceCheckResult>,
    now: Long = System.currentTimeMillis()): List<RecordingSourceCandidate> = candidates.distinctBy { it.key }
    .sortedWith(compareBy<RecordingSourceCandidate> { candidate ->
        val meta = candidate.metadata
        val accepted = RecordingMatchPolicy.assess(expected,
            RecordingMetadata(meta.title, meta.artist, meta.durationMs, meta.version, meta.album),
            if (candidate.key.provider == "local") 8_000 else 4_000).accepted
        val decision = decisions[candidate.key]
        val status = checks[candidate.key]?.takeIf { now - it.checkedAtMillis in 0..120_000 }?.status
        when {
            decision == SourceMatchDecision.REJECTED -> 6
            !accepted -> 5
            status in setOf(SourceCheckStatus.ADDRESS_RESOLVED, SourceCheckStatus.LOCAL_READABLE) -> 0
            status != null -> 4
            decision == SourceMatchDecision.CONFIRMED -> 1
            MusicSourceRegistry.canResolveAudio(candidate.key) -> 2
            else -> 3
        }
    }.thenBy { candidate -> kotlin.math.abs(candidate.metadata.durationMs - expected.durationMs) })
