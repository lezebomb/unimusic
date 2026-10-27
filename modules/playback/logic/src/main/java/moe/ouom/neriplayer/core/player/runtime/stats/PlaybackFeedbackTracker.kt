package moe.ouom.neriplayer.core.player.runtime.stats

import android.os.SystemClock
import java.util.UUID
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackEvent
import moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackKind

fun shouldRecordPlaybackSkip(userInitiated: Boolean, source: PlaybackCommandSource, previousKey: String?, targetKey: String?): Boolean =
    userInitiated && source == PlaybackCommandSource.LOCAL && previousKey != null && targetKey != null && previousKey != targetKey

/** Uses only confirmed engine-playing intervals; pauses/buffering and seeks never imply taste. */
class PlaybackFeedbackTracker(
    private val songKey: (SongItem) -> String,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val nowWallMs: () -> Long = System::currentTimeMillis,
    private val readClearedAt: () -> Long = { 0L }
) {
    private var song: SongItem? = null
    private var key: String? = null
    private var enabled = false
    private var playing = false
    private var startedAt: Long? = null
    private var heardMs = 0L
    private var sought = false
    private var skipped = false
    private var repeated = false
    private var lastPosition: Long? = null
    private var previousCompletedKey: String? = null
    private var lastEndAt: Long? = null
    private var observedClearedAt = 0L

    @Synchronized fun setEnabled(value: Boolean) {
        observeClearEpoch()
        if (enabled == value) return
        enabled = value
        previousCompletedKey = null
        lastEndAt = null
        resetCycle()
    }

    @Synchronized fun onSongChanged(next: SongItem?) {
        val nextKey = next?.let(songKey)
        if (key == nextKey) { song = next; return }
        song = next
        key = nextKey
        previousCompletedKey = null
        lastEndAt = null
        resetCycle()
    }

    @Synchronized fun onPlayingChanged(value: Boolean) {
        if (playing == value) return
        collect()
        playing = value
        startedAt = if (enabled && playing && song != null) nowElapsedMs() else null
    }

    @Synchronized fun onManualSeek(positionMs: Long) {
        collect()
        sought = true
        lastPosition = positionMs.coerceAtLeast(0L)
    }

    @Synchronized fun onProgress(positionMs: Long): List<PlaybackFeedbackEvent> {
        observeClearEpoch()
        if (!enabled || song == null) return emptyList()
        collect()
        val previous = lastPosition
        lastPosition = positionMs.coerceAtLeast(0L)
        if (!sought && hasPlaybackPositionWrapped(previous, positionMs, duration())) return onTrackEnded()
        return repeatEvidence()?.let(::listOf) ?: emptyList()
    }

    @Synchronized fun onTrackEnded(): List<PlaybackFeedbackEvent> {
        observeClearEpoch()
        if (!enabled || song == null) return emptyList()
        collect()
        val now = nowElapsedMs()
        // Media progress wrap and engine END may report the same boundary.
        if (heardMs < 1_000L && lastEndAt?.let { now - it in 0..1_000L } == true) return emptyList()
        val events = mutableListOf<PlaybackFeedbackEvent>()
        repeatEvidence()?.let(events::add)
        val complete = !sought && duration() > 0L && heardMs >= maxOf(1_000L, duration() * 4L / 5L)
        if (complete) events += event(PlaybackFeedbackKind.COMPLETE)
        previousCompletedKey = key.takeIf { complete }
        lastEndAt = now
        resetCycle()
        return events
    }

    @Synchronized fun onUserSkip(): List<PlaybackFeedbackEvent> {
        observeClearEpoch()
        if (!enabled || song == null) return emptyList()
        collect()
        val earlyLimit = minOf(30_000L, duration() / 4L)
        if (sought || skipped || heardMs < 2_000L || heardMs >= earlyLimit) return emptyList()
        skipped = true
        return listOf(event(PlaybackFeedbackKind.SKIP))
    }

    private fun repeatEvidence(): PlaybackFeedbackEvent? {
        if (duration() <= 0L || sought || repeated || key == null || previousCompletedKey != key || heardMs < 2_000L) return null
        repeated = true
        return event(PlaybackFeedbackKind.REPEAT)
    }

    private fun event(kind: PlaybackFeedbackKind) = PlaybackFeedbackEvent(
        UUID.randomUUID().toString(), kind, nowWallMs(), heardMs, duration()
    )

    private fun duration(): Long = song?.durationMs?.takeIf { it in 1..MAX_INTERVAL_MS } ?: 0L

    private fun collect() {
        val start = startedAt ?: return
        if (!enabled || !playing) return
        val now = nowElapsedMs()
        heardMs = (heardMs + (now - start).coerceAtLeast(0L)).coerceAtMost(MAX_INTERVAL_MS)
        startedAt = now
    }

    private fun observeClearEpoch() {
        val epoch = readClearedAt().coerceAtLeast(0L)
        if (epoch <= observedClearedAt) return
        observedClearedAt = epoch
        previousCompletedKey = null
        lastEndAt = null
        resetCycle()
    }

    private fun resetCycle() {
        heardMs = 0L
        sought = false
        skipped = false
        repeated = false
        lastPosition = null
        startedAt = if (enabled && playing && song != null) nowElapsedMs() else null
    }

    private companion object { const val MAX_INTERVAL_MS = 7L * 86_400_000L }
}
