package moe.ouom.neriplayer.data.unified

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SourceMatchRoomStoreTest {
    private val subject = SongItem(42, "晴天", "周杰伦", "叶惠美", 1, 269_000, null, channelId = "netease", audioId = "42")
    private val candidate = RecordingSourceCandidate(SongSourceKey("bilibili", "10", "11"),
        UnifiedSongMetadata("晴天", "周杰伦", "晴天", 269_000))

    @Test fun oldAutomaticEvidenceWithCollapsedArtistCreditsIsRejectedWithoutDeletingTheObservation() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = SourceMatchRoomStore(db) { 1000L }
            val duet = subject.copy(artist = "A / B")
            val matched = candidate.copy(metadata = candidate.metadata.copy(artist = "A/B"))
            val saved = store.rememberAutomatic(duet, matched, SourceDecisionStamp())!!
            val dao = db.sourceMatchDao()
            val row = dao.matches(saved.songId).single()
            dao.upsertMatch(row.copy(artist = "AB")) // Simulate older evidence accepted by flat normalization.
            assertTrue(store.automaticMatches(duet).isEmpty())
            assertEquals(saved.id, store.matches(duet).single().id)
            assertFalse(store.matches(duet).single().current)
        } finally { db.close() }
    }

    @Test fun neteaseAlternatePersistsForNativeSubjectsButCannotRememberItsOwnOriginalSource() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = SourceMatchRoomStore(db) { 1000L }
            val native = catalogPlaybackSong(CatalogTrack(SongSourceKey("qqmusic", "003OUlho2HcRHC"),
                candidate.metadata.copy(album = subject.album), null))!!
            val alternate = candidate.copy(key = SongSourceKey("netease", "42"), metadata = candidate.metadata.copy(album = subject.album))
            val saved = store.rememberAutomatic(native, alternate, SourceDecisionStamp())!!
            assertEquals(alternate.key, store.automaticMatches(native).single().candidate.key)
            assertEquals(saved.id, store.match(native, alternate.key)!!.id)
            assertNull(store.rememberAutomatic(subject, alternate, SourceDecisionStamp()))
            store.decide(native, alternate, SourceMatchDecision.REJECTED)
            assertTrue(store.automaticMatches(native).isEmpty())
            assertNull(store.rememberAutomatic(native, alternate, SourceDecisionStamp()))
        } finally { db.close() }
    }

    @Test fun decisionSurvivesReopenAndOldObservation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "source-match-test.db"
        context.deleteDatabase(file)
        var db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, file).allowMainThreadQueries().build()
        try {
            val first = SourceMatchRoomStore(db)
            val rejected = first.decide(subject, candidate, SourceMatchDecision.REJECTED)
            val rejectedStamp = first.decisionStamp(subject, candidate.key)
            assertTrue(first.isRejected(subject, candidate.key))
            db.close()
            db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, file).allowMainThreadQueries().build()
            val reopened = SourceMatchRoomStore(db)
            val oldWork = reopened.observe(subject, candidate)
            assertEquals(rejectedStamp, reopened.decisionStamp(subject, candidate.key))
            assertEquals(rejected.id, oldWork.id)
            assertEquals(rejected.songId, oldWork.songId)
            assertEquals(SourceMatchDecision.REJECTED, oldWork.decision)
            val confirmed = reopened.decide(subject, candidate, SourceMatchDecision.CONFIRMED)
            assertTrue(confirmed.current)
            assertTrue(confirmed.revision > rejected.revision)
            reopened.clear(subject, candidate.key)
            assertNotEquals(rejectedStamp, reopened.decisionStamp(subject, candidate.key))
            assertEquals(SourceMatchDecision.NONE, reopened.matches(subject).single().decision)
        } finally { db.close(); context.deleteDatabase(file) }
    }

    @Test fun confirmationCannotGrantCatalogRightsOrOverrideVersionConflicts() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = SourceMatchRoomStore(db)
            for (invalid in listOf(candidate.copy(metadata = candidate.metadata.copy(title = "晴天（Ｌｉｖｅ）")),
                candidate.copy(metadata = candidate.metadata.copy(durationMs = 0)),
                candidate.copy(key = SongSourceKey("kuwo", "09")))) {
                var refused = false
                try { store.decide(subject, invalid, SourceMatchDecision.CONFIRMED) }
                catch (_: IllegalArgumentException) { refused = true }
                assertTrue(refused)
            }
            assertTrue(store.matches(subject).isEmpty())
            store.decide(subject, candidate, SourceMatchDecision.CONFIRMED)
            val songId = store.subjectId(subject)
            assertEquals("METADATA_ONLY", db.unifiedLibraryDao().sources(songId).single().playbackMode)
        } finally { db.close() }
    }

    @Test fun changedMetadataRequiresExplicitReconfirmationAndPartsRemainDistinct() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = SourceMatchRoomStore(db)
            store.decide(subject, candidate, SourceMatchDecision.CONFIRMED)
            val edited = subject.copy(name = "另一首歌")
            store.observe(edited, candidate.copy(metadata = candidate.metadata.copy(title = "另一首歌")))
            assertFalse(store.matches(edited).single().current)
            val otherPart = candidate.copy(key = candidate.key.copy(subResourceId = "12"))
            store.decide(subject, otherPart, SourceMatchDecision.REJECTED)
            assertFalse(store.isRejected(subject, candidate.key))
            assertTrue(store.isRejected(subject, otherPart.key))
            assertEquals(2, store.matches(subject).size)
        } finally { db.close() }
    }

    @Test fun automaticCacheReopensAndExpires() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "auto-match-test.db"
        context.deleteDatabase(file)
        var time = 1_000L
        var db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, file).allowMainThreadQueries().build()
        try {
            val first = SourceMatchRoomStore(db) { time }
            first.observe(subject, candidate)
            assertTrue(first.automaticMatches(subject).isEmpty()) // A discovery/probe is not a successful auto resolution.
            val resolved = first.rememberAutomatic(subject, candidate, first.decisionStamp(subject, candidate.key))!!
            val resolvedAt = requireNotNull(resolved.automaticResolvedAt)
            db.close()
            db = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, file).allowMainThreadQueries().build()
            val reopened = SourceMatchRoomStore(db) { time }
            assertEquals(resolved.id, reopened.automaticMatches(subject).single().id)
            assertEquals(resolved.id, reopened.match(subject, candidate.key)?.id)
            time += 5_000
            reopened.observe(subject, candidate)
            assertEquals(resolved.automaticResolvedAt, reopened.automaticMatches(subject).single().automaticResolvedAt)
            assertTrue(reopened.automaticMatches(subject.copy(name = "另一首歌")).isEmpty())
            time = resolvedAt - 1 // Wall-clock rollback cannot extend trust.
            assertTrue(reopened.automaticMatches(subject).isEmpty())
            time = resolvedAt + 24 * 60 * 60_000L
            assertTrue(reopened.automaticMatches(subject).isEmpty())
            assertEquals(1, reopened.matches(subject).size) // Expiry preserves the observation and all user data.
            val nativeKeys = listOf(SongSourceKey("kuwo", "9"), SongSourceKey("qqmusic", "003OUlho2HcRHC"))
            for (key in nativeKeys) {
                val native = candidate.copy(key = key)
                assertNotNull(reopened.rememberAutomatic(subject, native, SourceDecisionStamp()))
                val song = catalogPlaybackSong(CatalogTrack(key, native.metadata, null))!!
                val playback = UnifiedPlaybackRoomStore(db)
                val target = playback.identify(song)
                assertEquals(key, sourceCandidateForSong(playback.load(target.songId)!!.song)!!.key)
            }
            assertEquals(nativeKeys.toSet(), reopened.automaticMatches(subject).map { it.candidate.key }.toSet())
        } finally { db.close(); context.deleteDatabase(file) }
    }

    @Test fun staleAutomaticWorkCannotUndoResetRejectionOrNewerSuccess() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        var time = 1_000L
        try {
            val store = SourceMatchRoomStore(db) { time }
            val stamp = store.decisionStamp(subject, candidate.key)
            val old = store.rememberAutomatic(subject, candidate, stamp)!!
            time++
            val fresh = store.rememberAutomatic(subject, candidate, stamp)!!
            store.invalidateAutomatic(subject, old)
            assertEquals(fresh.automaticResolvedAt, store.automaticMatches(subject).single().automaticResolvedAt)
            store.clear(subject, candidate.key)
            assertTrue(store.automaticMatches(subject).isEmpty())
            assertNull(store.rememberAutomatic(subject, candidate, stamp))
            val resetStamp = store.decisionStamp(subject, candidate.key)
            store.decide(subject, candidate, SourceMatchDecision.REJECTED)
            assertNull(store.rememberAutomatic(subject, candidate, resetStamp))
            store.invalidateAutomatic(subject, fresh)
            assertTrue(store.isRejected(subject, candidate.key))
            assertTrue(store.automaticMatches(subject).isEmpty())
            assertNull(store.rememberAutomatic(subject, candidate.copy(key = candidate.key.copy(subResourceId = "12"),
                metadata = candidate.metadata.copy(title = "晴天 Live")),
                SourceDecisionStamp()))
            assertNull(store.rememberAutomatic(subject, candidate.copy(key = SongSourceKey("kuwo", "09")), SourceDecisionStamp()))
        } finally { db.close() }
    }
    @Test fun olderAutomaticEvidenceNeedsRecheckWhileManualChoicesRemain() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = SourceMatchRoomStore(db) { 10_000L }
            val resolved = store.rememberAutomatic(subject, candidate, SourceDecisionStamp())!!
            val dao = db.sourceMatchDao()
            val row = dao.matches(resolved.songId).single()
            assertEquals(3, row.algorithmVersion)
            dao.upsertMatch(row.copy(algorithmVersion = 2))
            assertTrue(store.automaticMatches(subject).isEmpty())
            assertEquals(resolved.id, store.matches(subject).single().id)
            assertNotNull(store.rememberAutomatic(subject, candidate, SourceDecisionStamp()))
            assertEquals(1, store.automaticMatches(subject).size)
            val confirmed = store.decide(subject, candidate, SourceMatchDecision.CONFIRMED)
            assertEquals(SourceMatchDecision.CONFIRMED, store.matches(subject).single().decision)
            assertEquals(confirmed.revision, store.decisionStamp(subject, candidate.key).revision)
        } finally { db.close() }
    }

}
