package moe.ouom.neriplayer.data.recommendation

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.io.writeTextAtomically
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.unified.UnifiedLibraryRoomStore

/** A disposable, device-only cache. Cache or UUID lookup failures never prevent recommendations. */
class DailyRecommendationStore internal constructor(
    private val file: File,
    private val lookupSongUuid: suspend (String) -> String?,
    private val write: (File, String) -> Unit = { target, text -> target.writeTextAtomically(text) }
) {
    private val mutex = Mutex()
    private var memory: DailyRecommendationSnapshot? = null
    private var memoryStatisticsAvailable = true

    suspend fun getOrCreate(
        candidates: List<LocalRecommendationCandidate>,
        nowMillis: Long,
        forceRefresh: Boolean = false,
        evidenceContext: RecommendationEvidenceContext = RecommendationEvidenceContext(),
        build: suspend () -> LocalRecommendations
    ): LocalRecommendations = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!forceRefresh) {
                val cached = memory?.takeIf { it.matches(nowMillis, evidenceContext) }
                    ?: readSnapshot()?.takeIf { it.matches(nowMillis, evidenceContext) }?.also {
                        memory = it
                        memoryStatisticsAvailable = true
                    }
                if (cached != null) return@withLock restore(cached, candidates, memoryStatisticsAvailable)
            }
            if (candidates.isEmpty() && !forceRefresh) return@withLock LocalRecommendations()
            val fresh = build()
            val ids = (fresh.items + fresh.localFileItems).map { it.identityKey }.distinct().associateWith { key ->
                try { lookupSongUuid(key) } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    null
                }
            }
            val snapshot = DailyRecommendationSnapshot(
                BeijingRecommendationDay.key(nowMillis), LocalRecommendationRanker.ALGORITHM_VERSION,
                mapOf(
                    DailyRecommendationMode.LIBRARY to fresh.items.map { it.toSnapshotEntry(ids[it.identityKey]) },
                    DailyRecommendationMode.LOCAL_FILES to fresh.localFileItems.map { it.toSnapshotEntry(ids[it.identityKey]) }
                ), evidenceContext
            )
            memory = snapshot
            memoryStatisticsAvailable = fresh.statisticsAvailable
            // Keep a fallback in memory without overwriting a healthy disk snapshot; context changes retry it.
            if (fresh.statisticsAvailable && evidenceContext.feedbackAvailable) {
                try { write(file, DailyRecommendationSnapshotCodec.encode(snapshot)) } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                }
            }
            restore(snapshot, candidates, fresh.statisticsAvailable)
        }
    }

    private fun readSnapshot(): DailyRecommendationSnapshot? = try {
        if (!file.isFile || file.length() > DailyRecommendationSnapshotCodec.MAX_BYTES) null else {
            val bytes = ByteArray(DailyRecommendationSnapshotCodec.MAX_BYTES + 1)
            val count = file.inputStream().use { input ->
                var size = 0
                while (size < bytes.size) {
                    val read = input.read(bytes, size, bytes.size - size)
                    if (read < 0) break
                    size += read
                }
                size
            }
            require(count <= DailyRecommendationSnapshotCodec.MAX_BYTES)
            DailyRecommendationSnapshotCodec.decode(String(bytes, 0, count, Charsets.UTF_8))
        }
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        null
    }

    private fun restore(
        snapshot: DailyRecommendationSnapshot,
        candidates: List<LocalRecommendationCandidate>,
        statisticsAvailable: Boolean
    ): LocalRecommendations {
        val byDigest = LocalRecommendationRanker.canonicalCandidates(candidates)
            .associateBy { recommendationIdentityDigest(it.identityKey) }
        fun items(mode: DailyRecommendationMode): List<LocalRecommendation> = snapshot.modes.getValue(mode).mapNotNull { entry ->
            val candidate = byDigest[entry.localKeyDigest] ?: return@mapNotNull null
            if (mode == DailyRecommendationMode.LOCAL_FILES && candidate.source != LocalRecommendationSource.LOCAL_FILE) {
                return@mapNotNull null
            }
            // Resolve current metadata/playback references; removed songs never return from cached payloads.
            LocalRecommendation(candidate.song, candidate.identityKey, entry.reason, candidate.source, entry.songUuid)
        }
        return LocalRecommendations(items(DailyRecommendationMode.LIBRARY), items(DailyRecommendationMode.LOCAL_FILES), statisticsAvailable)
    }

    private fun LocalRecommendation.toSnapshotEntry(uuid: String?) = DailyRecommendationEntry(
        recommendationIdentityDigest(identityKey), uuid, reason, source
    )

    companion object {
        @Volatile private var instance: DailyRecommendationStore? = null

        fun getInstance(context: Context): DailyRecommendationStore = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                val unified by lazy { UnifiedLibraryRoomStore(NeriUserDataDatabase.getInstance(app)) }
                DailyRecommendationStore(
                    File(app.filesDir, "unimusic-recommendations/daily-v1.json"),
                    lookupSongUuid = { key -> unified.songForLegacy(key)?.id }
                )
            }.also { instance = it }
        }
    }
}
