package moe.ouom.neriplayer.core.player.runtime.stats

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackKind
import org.junit.Assert.*
import org.junit.Test

class PlaybackFeedbackTrackerTest {
    @Test fun pausedAndFreshlyRecoveredPlaybackDoNotBecomeDislikeEvidence() {
        var elapsed = 0L
        val tracker = PlaybackFeedbackTracker({ it.id.toString() }, { elapsed }, { 1_000_000 + elapsed })
        tracker.setEnabled(true);tracker.onSongChanged(song());tracker.onPlayingChanged(true)
        elapsed = 5_000;tracker.onPlayingChanged(false)
        elapsed = 25_000
        assertTrue(tracker.onUserSkip().isEmpty())
        tracker.onPlayingChanged(true)
        elapsed = 25_500
        assertTrue(tracker.onUserSkip().isEmpty())
        tracker.onPlayingChanged(false)
        elapsed = 27_000;tracker.onPlayingChanged(true)
        elapsed = 28_999
        assertTrue(tracker.onUserSkip().isEmpty())
        elapsed = 29_000
        assertEquals(PlaybackFeedbackKind.SKIP, tracker.onUserSkip().single().kind)
        assertTrue(tracker.onUserSkip().isEmpty())
    }

    @Test fun `skip requires confirmed listening and a distinct local user command`() {
        var elapsed = 0L
        val tracker = PlaybackFeedbackTracker({ it.id.toString() }, { elapsed }, { 1_000_000 + elapsed })
        tracker.setEnabled(true);tracker.onSongChanged(song());elapsed = 5000
        assertTrue(tracker.onUserSkip().isEmpty()) // Startup waiting is not heard time.
        tracker.onPlayingChanged(true);elapsed = 7500
        assertEquals(PlaybackFeedbackKind.SKIP, tracker.onUserSkip().single().kind)
        assertTrue(tracker.onUserSkip().isEmpty())
        assertTrue(shouldRecordPlaybackSkip(true, PlaybackCommandSource.LOCAL, "a", "b"))
        assertFalse(shouldRecordPlaybackSkip(false, PlaybackCommandSource.LOCAL, "a", "b"))
        assertFalse(shouldRecordPlaybackSkip(true, PlaybackCommandSource.REMOTE_SYNC, "a", "b"))
        assertFalse(shouldRecordPlaybackSkip(true, PlaybackCommandSource.LOCAL_SAFETY, "a", "b"))
        assertFalse(shouldRecordPlaybackSkip(true, PlaybackCommandSource.LOCAL, "a", "a"))
        tracker.onSongChanged(song().copy(id = 2L));elapsed += 2500;tracker.onManualSeek(100_000)
        assertTrue(tracker.onUserSkip().isEmpty())
    }

    @Test fun `natural completion and continued replay are counted once per cycle`() {
        var elapsed = 0L
        val tracker = PlaybackFeedbackTracker({ it.id.toString() }, { elapsed }, { 1_000_000 + elapsed })
        tracker.setEnabled(true);tracker.onSongChanged(song(10_000));tracker.onPlayingChanged(true)
        elapsed = 9000
        assertEquals(PlaybackFeedbackKind.COMPLETE, tracker.onTrackEnded().single().kind)
        elapsed = 9010;assertTrue(tracker.onTrackEnded().isEmpty())
        elapsed = 11000
        assertEquals(PlaybackFeedbackKind.REPEAT, tracker.onProgress(2000).single().kind)
        assertTrue(tracker.onProgress(2100).isEmpty())
        tracker.onManualSeek(9500);elapsed = 12000
        assertTrue(tracker.onTrackEnded().isEmpty())
    }

    @Test fun `seek disabled collection and clear fences never fabricate a completed play`() {
        var elapsed = 0L
        var cleared = 0L
        val tracker = PlaybackFeedbackTracker({ it.id.toString() }, { elapsed }, { 1_000_000 + elapsed }, { cleared })
        tracker.setEnabled(true);tracker.onSongChanged(song(10_000));tracker.onPlayingChanged(true)
        elapsed = 7000;tracker.onManualSeek(9500);elapsed = 8000
        assertTrue(tracker.onTrackEnded().isEmpty())
        tracker.onSongChanged(song(10_000).copy(id = 2L));elapsed = 18000;cleared = 1_017_000
        assertTrue(tracker.onTrackEnded().isEmpty())
        tracker.setEnabled(false);elapsed = 28000
        tracker.setEnabled(true);elapsed = 29000
        assertTrue(tracker.onTrackEnded().isEmpty())
        tracker.onSongChanged(song(0L).copy(id = 3L));elapsed = 32000
        assertTrue(tracker.onUserSkip().isEmpty())
    }

    private fun song(duration: Long = 180_000L) = SongItem(1L, "Fixture", "Artist", "Album", 0L, duration, null)
}
