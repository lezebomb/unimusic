package moe.ouom.neriplayer.core.player.policy.source

import org.junit.Assert.*
import org.junit.Test

class RecordingMatchPolicyTest {
    private val studio = RecordingMetadata("晴天", "周杰伦", 269_000)

    @Test fun versionConflictsOverridePerfectTextAndDuration() {
        listOf("Live", "Ｌｉｖｅ", "Ｃｏｖｅｒ", "Ｒｅｍｉｘ", "翻唱", "伴奏", "Remix", "Radio Edit", "Sped Up", "Remastered", "重录", "吉他教学", "Tutorial", "Reaction").forEach { tag ->
            val candidate = studio.copy(title = "晴天 ($tag)")
            assertEquals(tag, RecordingMismatch.VERSION, RecordingMatchPolicy.assess(studio, candidate).reason)
        }
        assertFalse(RecordingMatchPolicy.assess(studio, studio.copy(versionContext = "周杰伦演唱会现场")).accepted)
        assertFalse(RecordingMatchPolicy.assess(studio.copy(title = "晴天 (Live)"), studio).accepted)
    }

    @Test fun sameVersionAndCosmeticDifferencesCanMatch() {
        assertTrue(RecordingMatchPolicy.assess(studio, studio.copy(title = "晴天【Hi-Res】")).accepted)
        val live = RecordingMetadata("Love Story (Live)", "Taylor Swift", 235_000)
        assertTrue(RecordingMatchPolicy.assess(live, live.copy(title = "love story live", artist = "taylor-swift")).accepted)
        assertNotEquals(RecordingMatchPolicy.canonicalTitle("Live Forever"), RecordingMatchPolicy.canonicalTitle("Forever"))
    }

    @Test fun titleArtistAndDurationAreIndependentRequirements() {
        assertEquals(RecordingMismatch.TITLE, RecordingMatchPolicy.assess(studio, studio.copy(title = "晴天以后")).reason)
        assertEquals(RecordingMismatch.ARTIST, RecordingMatchPolicy.assess(studio, studio.copy(artist = "别人")).reason)
        assertEquals(RecordingMismatch.DURATION, RecordingMatchPolicy.assess(studio, studio.copy(durationMs = 300_000)).reason)
        assertEquals(RecordingMismatch.MISSING_METADATA, RecordingMatchPolicy.assess(studio, studio.copy(durationMs = 0)).reason)
        assertFalse(RecordingMatchPolicy.assess(studio.copy(artist = ""), studio).accepted)
    }
}
