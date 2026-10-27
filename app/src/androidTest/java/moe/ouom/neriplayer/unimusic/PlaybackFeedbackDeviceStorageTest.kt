package moe.ouom.neriplayer.unimusic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackEvent
import moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackKind
import moe.ouom.neriplayer.data.recommendation.LocalPlaybackFeedbackStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic storage evidence only. Does not start audio, replace the queue or change user records. */
@RunWith(AndroidJUnit4::class)
class PlaybackFeedbackDeviceStorageTest {
    @Test fun typedQueuePersistsDeduplicatesAndForgetsOnlyItsFixtures() = runBlocking {
        val store = LocalPlaybackFeedbackStore.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val first = "unimusic-storage-fixture-${UUID.randomUUID()}"
        val second = "unimusic-storage-fixture-${UUID.randomUUID()}"
        fun digest(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val at = System.currentTimeMillis()
        fun event(kind: PlaybackFeedbackKind) = PlaybackFeedbackEvent(UUID.randomUUID().toString(), kind, at,
            if (kind == PlaybackFeedbackKind.COMPLETE) 9000L else 2500L, 10_000L)
        try {
            withContext(Dispatchers.IO) {
                val skip = event(PlaybackFeedbackKind.SKIP)
                store.enqueue(first, listOf(skip, skip, event(PlaybackFeedbackKind.REPEAT)), 0)
                store.enqueue(second, listOf(event(PlaybackFeedbackKind.COMPLETE)), 0)
                store.flush()
            }
            assertFalse(store.hasPendingWrites())
            val saved = store.signals(System.currentTimeMillis(), 0).byDigest
            assertEquals(1.0, saved.getValue(digest(first)).skips, 0.01)
            assertEquals(1.0, saved.getValue(digest(first)).repeats, 0.01)
            assertEquals(1.0, saved.getValue(digest(second)).completions, 0.01)
            store.forget(setOf(first))
            val remaining = store.signals(System.currentTimeMillis(), 0).byDigest
            assertFalse(digest(first) in remaining)
            assertTrue(digest(second) in remaining)
        } finally { store.forget(setOf(first, second)) }
    }
}
