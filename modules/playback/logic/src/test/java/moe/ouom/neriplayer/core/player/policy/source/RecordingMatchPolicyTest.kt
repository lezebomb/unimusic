package moe.ouom.neriplayer.core.player.policy.source

import org.junit.Assert.*
import org.junit.Test

class RecordingMatchPolicyTest {
    private val studio = RecordingMetadata("晴天", "周杰伦", 269_000)

    @Test fun djVariantLabelsNeverBecomeStudioByKeepingTheOriginalArtistCredit() {
        val original = RecordingMetadata("Hello", "Adele", 258_000)
        for (label in listOf("Teddy Cream x Louis Quinn Booty", "Producer A Bootleg", "Producer B Rework", "DJ版")) {
            val variant = original.copy(title = "Hello ($label)")
            assertTrue(label, RecordingMatchPolicy.versions(variant).contains(moe.ouom.neriplayer.data.model.unified.RecordingVersion.REMIX))
            assertEquals(label, RecordingMismatch.VERSION, RecordingMatchPolicy.assess(original, variant).reason)
            assertTrue(RecordingMatchPolicy.assess(variant, variant).accepted)
        }
        assertFalse(RecordingMatchPolicy.assess(original.copy(title = "Hello (Producer A Bootleg)"),
            original.copy(title = "Hello (Producer B Bootleg)")).accepted)
        assertTrue(RecordingMatchPolicy.versions(original.copy(title = "Booty")).isEmpty())
        assertTrue(RecordingMatchPolicy.versions(original.copy(artist = "Producer A x Producer B Booty")).isEmpty())
        assertTrue(RecordingMatchPolicy.assess(original.copy(title = "Bootleg"), original.copy(title = "Bootleg [Official Audio]")).accepted)
        assertEquals(RecordingMismatch.VERSION, RecordingMatchPolicy.assess(original.copy(title = "Booty"),
            original.copy(title = "Booty (Producer A x Producer B Booty)")).reason)
        for (label in listOf("Mash Up版", "Mash-Up", "Mash_Up", "Live版", "Cover版", "Remix版")) {
            assertEquals(label, RecordingMismatch.VERSION, RecordingMatchPolicy.assess(original, original.copy(title = "Hello ($label)")).reason)
        }
        assertEquals(RecordingMismatch.VERSION, RecordingMatchPolicy.assess(original.copy(title = "Hello (Cover版)"),
            original.copy(title = "Hello (AI Cover版)")).reason)
        assertTrue(RecordingMatchPolicy.versions(original.copy(title = "Clover Olive Mashable")).isEmpty())
    }

    @Test fun collaborativeArtistCreditsCannotCollapseIntoADifferentPerformer() {
        val duet = studio.copy(artist = "A / B")
        assertEquals(RecordingMismatch.ARTIST, RecordingMatchPolicy.assess(duet, duet.copy(artist = "AB")).reason)
        assertEquals(RecordingMismatch.ARTIST, RecordingMatchPolicy.assess(duet.copy(artist = "AB"), duet).reason)
        assertTrue(RecordingMatchPolicy.assess(duet, duet.copy(artist = "b ＆ a")).accepted)
        assertTrue(RecordingMatchPolicy.assess(duet.copy(artist = "Taylor Swift"), duet.copy(artist = "taylor-swift")).accepted)
        assertFalse(RecordingMatchPolicy.assess(duet, duet.copy(artist = "A / ")).accepted)
        assertEquals(RecordingMismatch.ARTIST, RecordingMatchPolicy.assess(duet.copy(artist = "AC/DC"), duet.copy(artist = "ACDC")).reason)
    }

    @Test fun explicitSyntheticAliasCanMatchItsOwnVersionButNeverOriginalOrHumanCover() {
        val title = studio.copy(title = "晴天 (AI generated vocals)")
        val alias = studio.copy(versionContext = "AI generated vocals")
        assertEquals("晴天", RecordingMatchPolicy.canonicalTitle(title.title))
        assertTrue(RecordingMatchPolicy.assess(title, alias).accepted)
        assertEquals(RecordingMismatch.VERSION, RecordingMatchPolicy.assess(studio, title).reason)
        assertEquals(RecordingMismatch.VERSION, RecordingMatchPolicy.assess(studio.copy(title = "晴天 (Cover)"), title).reason)
    }

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
    @Test fun explicitSyntheticVocalsCannotPassAsTheOriginalOrAHumanCover() {
        for (tag in listOf("AI周杰伦音色", "ＡＩ生成歌声", "AI generated vocals", "AI Cover", "voice cloning", "人声合成")) {
            val candidate = studio.copy(versionContext = tag)
            assertEquals(tag, RecordingMismatch.VERSION, RecordingMatchPolicy.assess(studio, candidate).reason)
            assertEquals(tag, RecordingMismatch.VERSION,
                RecordingMatchPolicy.assess(studio.copy(version = moe.ouom.neriplayer.data.model.unified.RecordingVersion.COVER),
                    candidate.copy(version = moe.ouom.neriplayer.data.model.unified.RecordingVersion.COVER)).reason)
        }
    }

    @Test fun versionWordsInsideTheActualTitleCannotHideAnAddedVersionLabel() {
        for ((title, tag) in listOf("Live Forever" to "Live", "Cover Me" to "Cover", "Remix of Love" to "Remix")) {
            val original = RecordingMetadata(title, "Original Artist", 180_000)
            assertEquals(RecordingMismatch.VERSION, RecordingMatchPolicy.assess(original, original.copy(title = "$title ($tag)")).reason)
            assertTrue(RecordingMatchPolicy.assess(original, original.copy(title = "$title [Official Audio]")).accepted)
        }
    }

    @Test fun aiNamedArtistsAndTitlesAreNotSyntheticWithoutAnExplicitVoiceSignal() {
        for (metadata in listOf(RecordingMetadata("Story", "AI", 180_000), RecordingMetadata("AI", "Artist", 180_000),
            RecordingMetadata("Song", "Aimer", 180_000, versionContext = "Aimer 音色介绍"))) {
            assertFalse(RecordingMatchPolicy.versions(metadata).contains(moe.ouom.neriplayer.data.model.unified.RecordingVersion.COVER))
            assertTrue(RecordingMatchPolicy.assess(metadata, metadata).accepted)
        }
    }

}
