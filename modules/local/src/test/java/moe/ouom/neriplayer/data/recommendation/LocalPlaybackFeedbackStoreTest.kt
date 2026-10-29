package moe.ouom.neriplayer.data.recommendation

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackEvent
import moe.ouom.neriplayer.data.model.stats.PlaybackFeedbackKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPlaybackFeedbackStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 1_800_000_000_000L
    private fun event(at: Long = now) = PlaybackFeedbackEvent(UUID.randomUUID().toString(), PlaybackFeedbackKind.SKIP, at, 2500, 180_000)

    @Test fun `events deduplicate survive reopening and cannot cross clear or retention fences`() = runTest {
        val file = File(temporary.root, "events.json")
        val key = "private-local-path-and-source-id"
        val store = LocalPlaybackFeedbackStore(file, now = { now })
        val first = event()
        store.record(key, first);store.record(key, first)
        assertEquals(1L, store.revision.value)
        assertFalse(file.readText().contains(key))
        val reopened = LocalPlaybackFeedbackStore(file, now = { now })
        assertEquals(1, reopened.signals(now, 0).byDigest.size)
        assertEquals(1.0, reopened.signals(now, 0).byDigest.getValue(recommendationIdentityDigest(key)).skips, 0.001)
        reopened.clearBefore(now)
        reopened.record(key, first)
        assertTrue(reopened.signals(now, 0).byDigest.isEmpty())
        now += 1000;reopened.record(key, event())
        assertEquals(1, reopened.signals(now, 0).byDigest.size)
        reopened.forget(setOf(key))
        assertTrue(reopened.signals(now, 0).byDigest.isEmpty())
        reopened.record(key, event());now += 31L * 86_400_000L
        assertTrue(reopened.signals(now, 0).byDigest.isEmpty())
    }

    @Test fun `disabled recording persists and clear fences reject queued old evidence`() = runTest {
        val file = File(temporary.root, "controls.json")
        val store = LocalPlaybackFeedbackStore(file, now = { now })
        store.record("a", event())
        now += 1000
        store.setEnabled(false)
        assertFalse(store.signals(now, 0).enabled)
        assertEquals(now, store.signals(now, 0).clearedAt)
        now += 1000
        store.record("b", event())
        val reopened = LocalPlaybackFeedbackStore(file, now = { now })
        assertFalse(reopened.signals(now, 0).enabled)
        assertEquals(now - 1000, reopened.signals(now, 0).clearedAt)
        assertTrue(reopened.signals(now, 0).byDigest.isEmpty())
        val beforeEnabled = event()
        now += 1000
        reopened.setEnabled(true)
        reopened.record("b", beforeEnabled)
        assertTrue(reopened.signals(now, 0).byDigest.isEmpty())
        now += 1000
        val beforeClear = event()
        reopened.record("a", beforeClear)
        assertEquals(1, reopened.signals(now, 0).byDigest.size)
        now += 1000
        reopened.clearSignals()
        assertEquals(now, reopened.signals(now, 0).clearedAt)
        reopened.record("a", beforeClear)
        assertTrue(reopened.signals(now, 0).enabled)
        assertTrue(reopened.signals(now, 0).byDigest.isEmpty())
        now += 1000
        reopened.record("b", event())
        assertEquals(1, reopened.signals(now, 0).byDigest.size)
    }

    @Test fun `queued writes flush without changing unrelated evidence and failed writes preserve disk`() = runTest {
        val file = File(temporary.root, "queued.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = LocalPlaybackFeedbackStore(file, now = { now }, writerScope = scope)
            withContext(Dispatchers.IO) {
                val first = event()
                store.enqueue("a", listOf(first, first), 0)
                store.enqueue("b", listOf(event()), 0)
                store.flush()
            }
            assertFalse(store.hasPendingWrites())
            assertEquals(2, store.signals(now, 0).byDigest.size)
            store.forget(setOf("a"))
            assertEquals(setOf(recommendationIdentityDigest("b")), store.signals(now, 0).byDigest.keys)
            val original = file.readText()
            val failing = LocalPlaybackFeedbackStore(file, now = { now }, write = { _, _ -> throw IOException("full") })
            try { failing.record("c", event());fail("Expected failure") } catch (_: IOException) { }
            assertEquals(original, file.readText())
            assertEquals(1, failing.signals(now, 0).byDigest.size)
            val unknown = File(temporary.root, "future.json").apply { writeText("{\"schemaVersion\":99}") }
            val untouched = unknown.readText()
            try { LocalPlaybackFeedbackStore(unknown).record("x", event());fail("Expected unsupported file") } catch (_: IOException) { }
            assertEquals(untouched, unknown.readText())
        } finally { scope.cancel() }
    }
}
