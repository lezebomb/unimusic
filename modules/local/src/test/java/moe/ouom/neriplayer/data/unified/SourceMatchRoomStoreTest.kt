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
                candidate.copy(key = SongSourceKey("kuwo", "9")))) {
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
}
