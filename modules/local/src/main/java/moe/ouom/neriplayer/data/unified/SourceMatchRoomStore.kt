package moe.ouom.neriplayer.data.unified

import androidx.room.withTransaction
import java.util.UUID
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.unified.SongMatchEntity
import moe.ouom.neriplayer.data.local.database.entity.unified.UserMatchOverrideEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.*

/** User decisions are separate from observations so stale automatic work cannot erase a rejection. */
class SourceMatchRoomStore(private val db: NeriUserDataDatabase, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        // v1: discovery/probe evidence; v2: strict automatic match + a fresh eligible address.
        // Reuses the existing algorithm_version column; no schema or user-data migration.
        private const val AUTOMATIC_RESOLUTION_VERSION = 2
        private const val AUTOMATIC_TTL_MS = 24 * 60 * 60_000L
    }
    private val dao = db.sourceMatchDao()
    private val library = db.unifiedLibraryDao()
    private val unified = UnifiedLibraryRoomStore(db, now)

    suspend fun matches(song: SongItem): List<RecordingSourceMatch> = db.withTransaction {
        val id = subjectId(song)
        matchesForSubject(id, song)
    }

    /** No implicit registration: an incorrect UUID must never resolve another queue recording. */
    suspend fun matches(songId: String, song: SongItem): List<RecordingSourceMatch> = db.withTransaction {
        if (!canonicalUuid(songId) || library.sourceForLegacy(song.stableKey())?.songId != songId) {
            return@withTransaction emptyList()
        }
        matchesForSubject(songId, song)
    }

    private suspend fun matchesForSubject(id: String, song: SongItem): List<RecordingSourceMatch> {
        val fingerprint = sourceMatchFingerprint(sourceMatchMetadata(song))
        return dao.matches(id).map { row -> model(row, dao.override(row.matchId), fingerprint, sourceMatchMetadata(song)) }
            .sortedWith(compareBy<RecordingSourceMatch> { it.decision != SourceMatchDecision.CONFIRMED }.thenByDescending { it.updatedAt })
    }

    suspend fun subjectId(song: SongItem): String = db.withTransaction {
        val legacy = song.stableKey()
        library.sourceForLegacy(legacy)?.let { return@withTransaction it.songId }
        val track = db.localPlaylistDao().getTracksByIdentityKeys(listOf(legacy)).firstOrNull()
        val key = track?.let(::legacyUnifiedSourceKey) ?: sourceCandidateForSong(song)?.key
            ?: SongSourceKey("legacy", UUID.nameUUIDFromBytes(legacy.toByteArray(Charsets.UTF_8)).toString())
        unified.registerSource(key, sourceMatchMetadata(song), legacy).id
    }

    suspend fun isRejected(song: SongItem, key: SongSourceKey): Boolean =
        decisionStamp(song, key).decision == SourceMatchDecision.REJECTED

    suspend fun decisionStamp(song: SongItem, key: SongSourceKey): SourceDecisionStamp = db.withTransaction {
        val row = find(subjectId(song), key) ?: return@withTransaction SourceDecisionStamp()
        val override = dao.override(row.matchId) ?: return@withTransaction SourceDecisionStamp()
        SourceDecisionStamp(SourceMatchDecision.valueOf(override.decision), override.revision)
    }

    suspend fun observe(song: SongItem, candidate: RecordingSourceCandidate): RecordingSourceMatch = db.withTransaction {
        val row = saveObservation(song, candidate)
        model(row, dao.override(row.matchId), sourceMatchFingerprint(sourceMatchMetadata(song)), sourceMatchMetadata(song))
    }

    suspend fun automaticMatches(song: SongItem): List<RecordingSourceMatch> = db.withTransaction {
        val timestamp = now()
        if (timestamp <= 0) return@withTransaction emptyList()
        val subject = sourceMatchMetadata(song)
        val fingerprint = sourceMatchFingerprint(subject)
        dao.automaticMatches(subjectId(song), fingerprint, maxOf(0, timestamp - AUTOMATIC_TTL_MS),
            timestamp, AUTOMATIC_RESOLUTION_VERSION).map { model(it, dao.override(it.matchId), fingerprint, subject) }
            .filter { it.current && it.decision == SourceMatchDecision.NONE }
    }

    /** Exact-key refresh avoids walking the 200-row display list while an address is in flight. */
    suspend fun match(song: SongItem, key: SongSourceKey): RecordingSourceMatch? = db.withTransaction {
        val row = find(subjectId(song), key) ?: return@withTransaction null
        val subject = sourceMatchMetadata(song)
        model(row, dao.override(row.matchId), sourceMatchFingerprint(subject), subject)
    }

    /** Compare-and-write: a rejection/reset/confirmation made during resolution always wins. */
    suspend fun rememberAutomatic(song: SongItem, candidate: RecordingSourceCandidate,
        stamp: SourceDecisionStamp): RecordingSourceMatch? = db.withTransaction {
        if (candidate.key.provider !in setOf("local", "bilibili") || !nativeMatchKey(candidate.key) ||
            stamp.decision != SourceMatchDecision.NONE || decisionStamp(song, candidate.key) != stamp ||
            !RecordingMatchPolicy.assess(sourceMatchMetadata(song).recording(), candidate.metadata.recording(),
                if (candidate.key.provider == "local") 8_000 else 4_000).accepted) return@withTransaction null
        val row = saveObservation(song, candidate, automatic = true)
        model(row, dao.override(row.matchId), sourceMatchFingerprint(sourceMatchMetadata(song)), sourceMatchMetadata(song))
    }

    /** Invalidating stale automatic evidence must not clear a newer success or a user override. */
    suspend fun invalidateAutomatic(song: SongItem, match: RecordingSourceMatch) = db.withTransaction {
        val row = find(subjectId(song), match.candidate.key) ?: return@withTransaction
        if (row.matchId == match.id && row.algorithmVersion == AUTOMATIC_RESOLUTION_VERSION &&
            row.observedAt == match.automaticResolvedAt &&
            (dao.override(row.matchId)?.revision ?: 0) == match.revision) {
            dao.upsertMatch(row.copy(algorithmVersion = 1))
        }
    }

    suspend fun decide(song: SongItem, candidate: RecordingSourceCandidate, decision: SourceMatchDecision): RecordingSourceMatch = db.withTransaction {
        require(validMatchKey(candidate.key)) { "Unsupported source identity" }
        require(sourceCandidateForSong(song)?.key != candidate.key) { "Cannot override the original source" }
        if (decision == SourceMatchDecision.CONFIRMED) {
            require(nativeMatchKey(candidate.key)) { "This source has catalog capability only" }
            require(RecordingMatchPolicy.assess(sourceMatchMetadata(song).recording(), candidate.metadata.recording(),
                if (candidate.key.provider == "local") 8_000 else 4_000).accepted) { "Recording metadata or version does not match" }
        }
        val row = saveObservation(song, candidate, replaceConfirmed = true)
        val previous = dao.override(row.matchId)
        val override = UserMatchOverrideEntity(row.matchId, decision.name, Math.addExact(previous?.revision ?: 0, 1), now())
        dao.upsertOverride(override)
        model(row, override, sourceMatchFingerprint(sourceMatchMetadata(song)), sourceMatchMetadata(song))
    }

    suspend fun clear(song: SongItem, key: SongSourceKey) = db.withTransaction {
        val row = find(subjectId(song), key) ?: return@withTransaction
        val previous = dao.override(row.matchId)
        dao.upsertOverride(UserMatchOverrideEntity(row.matchId, SourceMatchDecision.NONE.name,
            Math.addExact(previous?.revision ?: 0, 1), now()))
        dao.upsertMatch(row.copy(algorithmVersion = 1))
    }

    private suspend fun saveObservation(song: SongItem, candidate: RecordingSourceCandidate,
        replaceConfirmed: Boolean = false, automatic: Boolean = false): SongMatchEntity {
        require(validMatchKey(candidate.key)) { "Unsupported source identity" }
        val id = subjectId(song)
        val row = find(id, candidate.key)
        // A new background observation cannot silently reconfirm edited recording metadata.
        if (!replaceConfirmed && row != null && dao.override(row.matchId)?.decision == SourceMatchDecision.CONFIRMED.name) return row
        val value = SongMatchEntity(row?.matchId ?: UUID.randomUUID().toString(), id, candidate.key.provider,
            candidate.key.resourceId, candidate.key.subResourceId, candidate.key.recordingContext,
            candidate.metadata.title, candidate.metadata.artist, candidate.metadata.album, candidate.metadata.durationMs,
            candidate.metadata.version.name, sourceMatchFingerprint(sourceMatchMetadata(song)),
            if (automatic) AUTOMATIC_RESOLUTION_VERSION else 1,
            if (automatic && row?.algorithmVersion == AUTOMATIC_RESOLUTION_VERSION)
                maxOf(now(), Math.addExact(row.observedAt, 1)) else now())
        // Re-listing/probing an unchanged candidate neither promotes it nor extends its cache TTL.
        if (!automatic && !replaceConfirmed && row?.algorithmVersion == AUTOMATIC_RESOLUTION_VERSION &&
            row.copy(algorithmVersion = 1, observedAt = value.observedAt) == value) return row
        dao.upsertMatch(value) // Upsert, never REPLACE: preserves the separate override row.
        return value
    }

    private suspend fun find(id: String, key: SongSourceKey) =
        dao.match(id, key.provider, key.resourceId, key.subResourceId, key.recordingContext)

    private fun model(row: SongMatchEntity, override: UserMatchOverrideEntity?, fingerprint: String,
        subject: UnifiedSongMetadata): RecordingSourceMatch {
        val key = SongSourceKey(row.provider, row.resourceId, row.subResourceId, row.recordingContext)
        val metadata = UnifiedSongMetadata(row.title, row.artist, row.album, row.durationMs,
            runCatching { RecordingVersion.valueOf(row.recordingVersion) }.getOrDefault(RecordingVersion.UNKNOWN))
        return RecordingSourceMatch(row.matchId, row.songId, RecordingSourceCandidate(key, metadata),
            override?.decision?.let(SourceMatchDecision::valueOf) ?: SourceMatchDecision.NONE,
            override?.revision ?: 0, override?.updatedAt ?: row.observedAt,
            row.subjectFingerprint == fingerprint && nativeMatchKey(key) &&
                RecordingMatchPolicy.assess(subject.recording(), metadata.recording(), if (key.provider == "local") 8_000 else 4_000).accepted,
            row.observedAt.takeIf { row.algorithmVersion == AUTOMATIC_RESOLUTION_VERSION })
    }
}
