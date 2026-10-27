package moe.ouom.neriplayer.data.recommendation

import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalRecommendationFeedbackStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_793_116_800_000L

    @Test
    fun `preferences survive reopening expire and contain no raw identity`() = runTest {
        val file = File(temporary.root, "feedback.json")
        val key = "netease:123456:private-identity"
        val store = LocalRecommendationFeedbackStore(file)
        store.dismiss(key, now)
        assertEquals(setOf(recommendationIdentityDigest(key)), LocalRecommendationFeedbackStore(file).activeDigests(now + 1))
        assertFalse(file.readText().contains(key))
        assertFalse(file.readText().contains("private-identity"))
        assertFalse(store.activeDigests(now).contains(recommendationIdentityDigest("netease:654321:private-identity")))
        assertTrue(store.activeDigests(now + LocalRecommendationFeedbackStore.DISMISS_DURATION_MS).isEmpty())
        store.reset()
        assertTrue(LocalRecommendationFeedbackStore(file).activeDigests(now).isEmpty())
    }

    @Test
    fun `concurrent decisions survive and stale undo cannot remove a newer dismissal`() = runTest {
        val store = LocalRecommendationFeedbackStore(File(temporary.root, "concurrent.json"))
        val first = async { store.dismiss("recording-a", now) }
        val second = async { store.dismiss("recording-b", now) }
        val old = first.await()
        val other = second.await()
        val newer = store.dismiss("recording-a", now)
        store.undo(old)
        assertEquals(2, store.activeDigests(now).size)
        store.undo(newer)
        assertEquals(setOf(other.digest), store.activeDigests(now))
        assertEquals(4L, store.revision.value)
    }

    @Test
    fun `failed writes and unknown files preserve previous preferences`() = runTest {
        val file = File(temporary.root, "write.json")
        val initial = LocalRecommendationFeedbackStore(file)
        initial.dismiss("keep", now)
        val original = file.readText()
        val failing = LocalRecommendationFeedbackStore(file, write = { _, _ -> throw IOException("disk unavailable") })
        try { failing.dismiss("not-saved", now); fail("Expected write failure") } catch (_: IOException) { }
        assertEquals(original, file.readText())
        assertEquals(setOf(recommendationIdentityDigest("keep")), failing.activeDigests(now))
        assertEquals(0L, failing.revision.value)
        val future = File(temporary.root, "future.json").apply { writeText("{\"schemaVersion\":99,\"dismissals\":[]}") }
        val unsupported = LocalRecommendationFeedbackStore(future)
        val untouched = future.readText()
        try { unsupported.dismiss("no-overwrite", now); fail("Expected unsupported version") } catch (_: IOException) { }
        assertEquals(untouched, future.readText())
    }
}
