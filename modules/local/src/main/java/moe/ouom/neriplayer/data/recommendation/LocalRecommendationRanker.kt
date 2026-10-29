package moe.ouom.neriplayer.data.recommendation

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.unified.RecordingMatchPolicy
import kotlin.math.ln

enum class LocalRecommendationReason { EXPLORE_LIBRARY, RECENTLY_ADDED, FAMILIAR_ARTIST, REDISCOVER, LESS_PLAYED }

enum class LocalRecommendationSource { LOCAL_FILE, NETEASE, BILIBILI, YOUTUBE_MUSIC, LIBRARY }

data class LocalRecommendationCandidate(
    val song: SongItem,
    val identityKey: String,
    val source: LocalRecommendationSource,
    val favorite: Boolean = false
)

data class LocalRecommendation(
    val song: SongItem,
    val identityKey: String,
    val reason: LocalRecommendationReason,
    val source: LocalRecommendationSource,
    val unifiedSongId: String? = null
)

/**
 * Read-only rules over existing library metadata and sampled listening aggregates.
 * An absent aggregate means unknown history, never a skip, dislike or completed play.
 * Identity keys are supplied by the existing repository adapter; titles never merge recordings.
 */
object LocalRecommendationRanker {
    const val ALGORITHM_VERSION = 5
    private const val DAY_MS = 86_400_000L

    fun rank(
        candidates: List<LocalRecommendationCandidate>,
        statistics: List<TrackStat>,
        nowMillis: Long,
        limit: Int = 12,
        feedback: Map<String, LocalPlaybackSignal> = emptyMap(),
        checkCancellation: () -> Unit = {}
    ): List<LocalRecommendation> {
        checkCancellation()
        if (limit <= 0) return emptyList()
        val today = BeijingRecommendationDay.startMillis(nowMillis)
        val stats = statistics.associateBy { checkCancellation(); it.identityKey }
        val artistAffinity = mutableMapOf<String, Double>()
        statistics.forEach { track ->
            checkCancellation()
            val credits = artistKeys(track.customArtist ?: track.artist)
            if (credits.isNotEmpty()) {
                val weight = ln(1.0 + track.totalListenMs.coerceAtLeast(0L) / 180_000.0) /
                    (1.0 + ageDays(today, track.lastPlayedAt) / 30.0) / credits.size
                credits.forEach { artist -> artistAffinity[artist] = artistAffinity.getOrDefault(artist, 0.0) + weight }
            }
        }
        val ranked = canonicalCandidates(candidates, checkCancellation)
            .map { candidate -> checkCancellation(); score(candidate, stats[candidate.identityKey], artistAffinity, today, feedback[candidate.identityKey]) }
            .toMutableList()
        val selected = ArrayList<LocalRecommendation>(limit.coerceAtMost(ranked.size))
        val artistCounts = mutableMapOf<String, Int>()
        while (selected.size < limit && ranked.isNotEmpty()) {
            checkCancellation()
            // Normalize previous credits once per selection, not once per comparator invocation.
            val previousArtists = selected.lastOrNull()?.song?.let { artistKeys(it.customArtist ?: it.artist) }.orEmpty()
            // A greedy diversity penalty also separates adjacent tracks sharing any credited artist.
            val best = ranked.maxWithOrNull(compareBy<Scored> {
                checkCancellation()
                it.score - (it.artists.maxOfOrNull { artist -> artistCounts.getOrDefault(artist, 0) } ?: 0) * 22.0 -
                    if (it.artists.any { artist -> artist in previousArtists }) 12.0 else 0.0
            }.thenByDescending { it.candidate.identityKey }) ?: break
            ranked.remove(best)
            best.artists.forEach { artist -> artistCounts[artist] = artistCounts.getOrDefault(artist, 0) + 1 }
            selected += LocalRecommendation(best.candidate.song, best.candidate.identityKey, best.reason, best.candidate.source)
        }
        return selected
    }

    internal fun canonicalCandidates(
        candidates: List<LocalRecommendationCandidate>, checkCancellation: () -> Unit = {}
    ): List<LocalRecommendationCandidate> =
        candidates.asSequence().onEach { checkCancellation() }.filter { candidate ->
            candidate.source == LocalRecommendationSource.LOCAL_FILE ||
                (candidate.source != LocalRecommendationSource.YOUTUBE_MUSIC &&
                    candidate.song.channelId?.lowercase() !in setOf("qqmusic", "qq", "youtube", "youtubemusic", "youtube_music"))
        }.groupBy { it.identityKey }.values.map { duplicates ->
            checkCancellation()
            duplicates.minWith(compareBy<LocalRecommendationCandidate>(
                { checkCancellation(); it.source != LocalRecommendationSource.LOCAL_FILE }, { it.source.ordinal }, { !it.favorite },
                { it.song.customName ?: it.song.name }, { it.song.customArtist ?: it.song.artist },
                { -(it.song.membershipAddedAtMs?.takeIf { time -> time > 0L } ?: it.song.addedAt).coerceAtLeast(0L) },
                { it.song.mediaUri.orEmpty() }, { it.song.localFilePath.orEmpty() }
            )).copy(favorite = duplicates.any { checkCancellation(); it.favorite })
        }

    private data class Scored(
        val candidate: LocalRecommendationCandidate,
        val score: Double,
        val artists: Set<String>,
        val reason: LocalRecommendationReason
    )

    private fun score(
        candidate: LocalRecommendationCandidate,
        stat: TrackStat?,
        affinity: Map<String, Double>,
        today: Long,
        feedback: LocalPlaybackSignal?
    ): Scored {
        val artists = artistKeys(candidate.song.customArtist ?: candidate.song.artist)
        val age = stat?.takeIf { it.lastPlayedAt > 0L }?.let { ageDays(today, it.lastPlayedAt) }
        val listens = stat?.totalListenMs?.coerceAtLeast(0L) ?: 0L
        val plays = stat?.playCount?.coerceAtLeast(0) ?: 0
        val knownListening = stat != null && (listens > 0L || plays > 0)
        val artistScore = artists.sumOf { affinity[it] ?: 0.0 }
            .coerceAtMost(4.0) * 6.0
        val addedAt = candidate.song.membershipAddedAtMs?.takeIf { it > 0L } ?: candidate.song.addedAt
        val recentlyAdded = addedAt in 1..(today + DAY_MS) && ageDays(today, addedAt) <= 14.0
        val repeatPenalty = when {
            age == null -> 0.0
            age < 1.0 -> 48.0
            age < 3.0 -> 28.0
            age < 7.0 -> 12.0
            else -> 0.0
        }
        val familiarity = ln(1.0 + plays.toDouble()) * 3.0 + ln(1.0 + listens / 180_000.0) * 2.0
        val rediscover = knownListening && age != null && age >= 14.0
        val dailyRotation = Math.floorMod((candidate.identityKey + today / DAY_MS).hashCode(), 1_000) / 250.0
        val evidence = feedback?.let { -it.skips.coerceIn(0.0, 3.0) * 12.0 +
            it.completions.coerceIn(0.0, 2.0) * 3.0 + it.repeats.coerceIn(0.0, 2.0) * 6.0 } ?: 0.0
        val score = evidence + 40.0 + artistScore + dailyRotation + (if (candidate.favorite) 5.0 else 0.0) +
            (if (recentlyAdded) 12.0 else 0.0) + (if (rediscover) 10.0 else 0.0) +
            (if (!knownListening) 8.0 else 0.0) - repeatPenalty - familiarity
        val reason = when {
            recentlyAdded -> LocalRecommendationReason.RECENTLY_ADDED
            rediscover -> LocalRecommendationReason.REDISCOVER
            artistScore >= 6.0 -> LocalRecommendationReason.FAMILIAR_ARTIST
            knownListening && plays <= 1 && listens <= candidate.song.durationMs.coerceAtLeast(180_000L) * 2 ->
                LocalRecommendationReason.LESS_PLAYED
            else -> LocalRecommendationReason.EXPLORE_LIBRARY
        }
        return Scored(candidate, score, artists, reason)
    }

    private fun artistKeys(value: String): Set<String> = RecordingMatchPolicy.artistCredits(value)
        .takeIf { it.none(String::isEmpty) }?.toSet().orEmpty()
    private fun ageDays(today: Long, timestamp: Long): Double =
        (today - timestamp.coerceAtLeast(0L)).coerceAtLeast(0L) / DAY_MS.toDouble()
}
