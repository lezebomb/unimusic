package moe.ouom.neriplayer.data.recommendation

import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DailyRecommendationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-10-26T03:00:00Z").toEpochMilli()
    private val uuid = "9b90cdeb-25f9-423c-a312-9368f4bc67f5"

    @Test
    fun `reopen keeps daily order and real UUIDs but restores only current library metadata`() = runTest {
        val file = File(temporary.root, "daily.json")
        val local = candidate("7|__local_files__|content://private/music/7", "Private title", local = true)
        val remote = candidate("8|netease|https://private.invalid/song?token=secret", "Other private title")
        val library = listOf(local, remote)
        val queried = mutableListOf<String>()
        val store = DailyRecommendationStore(file, lookupSongUuid = { key ->
            queried += key
            if (key == local.identityKey) uuid else null
        })

        val first = store.getOrCreate(library, now) { recommendations(library) }
        assertEquals(uuid, first.items.first().unifiedSongId)
        assertNull(first.items.last().unifiedSongId)
        assertEquals(library.map { it.identityKey }, queried)
        val json = file.readText()
        listOf("content://", "https://", "/private/", "token=", "secret", "Private title", "localFilePath", "streamUrl").forEach {
            assertFalse("Snapshot leaked $it", json.contains(it))
        }
        assertEquals(setOf("schemaVersion", "day", "algorithmVersion", "evidenceContext", "modes"), JsonParser.parseString(json).asJsonObject.keySet())
        val decoded = DailyRecommendationSnapshotCodec.decode(json)
        assertEquals(uuid, decoded.modes.getValue(DailyRecommendationMode.LIBRARY).first().songUuid)
        assertTrue(decoded.modes.values.flatten().all { it.localKeyDigest.length == 64 })

        val reopened = DailyRecommendationStore(file, lookupSongUuid = { error("A cache hit must not query or invent UUIDs") })
        val renamed = remote.copy(song = remote.song.copy(name = "Current title", streamUrl = "https://current.invalid/play"))
        val updated = reopened.getOrCreate(listOf(renamed, local), now + 1_000) { error("A statistics update must preserve daily order") }
        assertEquals(first.items.map { it.identityKey }, updated.items.map { it.identityKey })
        assertEquals("Current title", updated.items.last().song.name)
        val removed = reopened.getOrCreate(listOf(renamed), now + 2_000) { error("Deletion should only filter cached identifiers") }
        assertEquals(listOf(remote.identityKey), removed.items.map { it.identityKey })
        assertTrue(removed.localFileItems.isEmpty())
    }

    @Test
    fun `Beijing midnight and explicit refresh rebuild while UTC midnight does not`() = runTest {
        val file = File(temporary.root, "day.json")
        val library = listOf(candidate("first", "First"), candidate("second", "Second"))
        val store = DailyRecommendationStore(file, lookupSongUuid = { null })
        val beijingStart = Instant.parse("2026-10-25T16:00:01Z").toEpochMilli()
        val afterUtcMidnight = Instant.parse("2026-10-26T00:00:01Z").toEpochMilli()
        val beforeBeijingMidnight = Instant.parse("2026-10-26T15:59:59Z").toEpochMilli()
        val nextDay = Instant.parse("2026-10-26T16:00:00Z").toEpochMilli()
        var builds = 0
        store.getOrCreate(library, beijingStart) { builds++; recommendations(library) }
        val unchanged = store.getOrCreate(library.reversed(), afterUtcMidnight) { error("Same Beijing day") }
        assertEquals(listOf("first", "second"), unchanged.items.map { it.identityKey })
        assertEquals(
            LocalRecommendationRanker.rank(library, emptyList(), beijingStart),
            LocalRecommendationRanker.rank(library.reversed(), emptyList(), afterUtcMidnight)
        )
        val refreshed = store.getOrCreate(library, beforeBeijingMidnight, forceRefresh = true) {
            builds++; recommendations(library.reversed())
        }
        assertEquals(listOf("second", "first"), refreshed.items.map { it.identityKey })
        store.getOrCreate(library, nextDay) { builds++; recommendations(library) }
        assertEquals(3, builds)
        assertEquals("2026-10-26", BeijingRecommendationDay.key(beforeBeijingMidnight))
        assertEquals("2026-10-27", DailyRecommendationSnapshotCodec.decode(file.readText()).day)
        assertEquals(nextDay, BeijingRecommendationDay.nextStartMillis(afterUtcMidnight))
    }

    @Test
    fun `corrupt unknown or oversized caches rebuild and failed IO still returns recommendations`() = runTest {
        val file = File(temporary.root, "invalid.json")
        val library = listOf(candidate("song", "Song"))
        listOf("{", "{\"schemaVersion\":99}", "x".repeat(DailyRecommendationSnapshotCodec.MAX_BYTES + 1)).forEach { invalid ->
            file.writeText(invalid)
            var builds = 0
            val result = DailyRecommendationStore(file, lookupSongUuid = { null }).getOrCreate(library, now) {
                builds++; recommendations(library)
            }
            assertEquals(1, builds)
            assertEquals("song", result.items.single().identityKey)
            assertEquals("2026-10-26", DailyRecommendationSnapshotCodec.decode(file.readText()).day)
        }
        val oldAlgorithm = file.readText().replace("\"algorithmVersion\":${LocalRecommendationRanker.ALGORITHM_VERSION}", "\"algorithmVersion\":999")
        file.writeText(oldAlgorithm)
        var builds = 0
        val failing = DailyRecommendationStore(file, lookupSongUuid = { throw IOException("UUID bridge unavailable") },
            write = { _, _ -> throw IOException("Cache storage unavailable") })
        val result = failing.getOrCreate(library, now) { builds++; recommendations(library) }
        assertEquals(1, builds)
        assertNull(result.items.single().unifiedSongId)
        assertEquals(result, failing.getOrCreate(library, now + 1) { error("Failed disk writes must retain the in-memory day") })
        val directoryInsteadOfFile = temporary.newFolder("unreadable-cache")
        assertEquals("song", DailyRecommendationStore(directoryInsteadOfFile, lookupSongUuid = { null })
            .getOrCreate(library, now) { recommendations(library) }.items.single().identityKey)
    }

    @Test fun `reopened snapshots respect cleared statistics feedback resets and disabled consent`() = runTest {
        val file = File(temporary.root, "context.json")
        val library = listOf(candidate("a", "First"), candidate("b", "Second"))
        val original = DailyRecommendationStore(file, lookupSongUuid = { null })
        original.getOrCreate(library, now) { recommendations(library) }
        // Legacy v1 snapshots without context remain readable for the untouched default only.
        val legacy = JsonParser.parseString(file.readText()).asJsonObject.apply { remove("evidenceContext") }
        file.writeText(legacy.toString())
        DailyRecommendationStore(file, lookupSongUuid = { null }).getOrCreate(library, now) { error("Healthy legacy hit") }
        val contexts = listOf(
            RecommendationEvidenceContext(statisticsClearedAt = now - 100),
            RecommendationEvidenceContext(statisticsClearedAt = now - 100, feedbackClearedAt = now - 50),
            RecommendationEvidenceContext(statisticsClearedAt = now - 100, feedbackClearedAt = now - 50, feedbackEnabled = false)
        )
        var builds = 0
        contexts.forEachIndexed { index, context ->
            val order = if (index % 2 == 0) library.reversed() else library
            val reopened = DailyRecommendationStore(file, lookupSongUuid = { null })
            val result = reopened.getOrCreate(library, now, evidenceContext = context) { builds++; recommendations(order) }
            assertEquals(order.map { it.identityKey }, result.items.map { it.identityKey })
            assertEquals(context, DailyRecommendationSnapshotCodec.decode(file.readText()).evidenceContext)
            assertEquals(result, reopened.getOrCreate(library, now + 1, evidenceContext = context) { error("Same context stays stable") })
            assertEquals(result, DailyRecommendationStore(file, lookupSongUuid = { null })
                .getOrCreate(library, now + 2, evidenceContext = context) { error("Context persists over restart") })
        }
        assertEquals(3, builds)
    }

    @Test fun `unavailable feedback avoids a biased snapshot and never overwrites healthy disk evidence`() = runTest {
        val file = File(temporary.root, "health.json")
        val library = listOf(candidate("a", "First"), candidate("b", "Second"))
        val store = DailyRecommendationStore(file, lookupSongUuid = { null })
        val healthy = store.getOrCreate(library, now) { recommendations(library) }
        val disk = file.readText()
        val unavailable = RecommendationEvidenceContext(feedbackAvailable = false)
        val fallback = store.getOrCreate(library, now + 1, evidenceContext = unavailable) { recommendations(library.reversed()) }
        assertEquals(library.reversed().map { it.identityKey }, fallback.items.map { it.identityKey })
        assertEquals(disk, file.readText())
        assertEquals(fallback, store.getOrCreate(library, now + 2, evidenceContext = unavailable) { error("Stable degraded memory") })
        assertEquals(healthy, store.getOrCreate(library, now + 3) { error("Healthy disk may be reused after recovery") })
    }

    @Test fun `cancelled UUID capture cannot replace a healthy memory or disk snapshot`() = runTest {
        val file = File(temporary.root, "cancelled.json")
        val library = listOf(candidate("a", "First"), candidate("b", "Second"))
        var cancelCapture = false
        val store = DailyRecommendationStore(file, lookupSongUuid = {
            if (cancelCapture) currentCoroutineContext().cancel()
            null
        })
        val healthy = store.getOrCreate(library, now) { recommendations(library) }
        val original = file.readText()
        cancelCapture = true
        val refresh = async { store.getOrCreate(library, now + 1, forceRefresh = true) { recommendations(library.reversed()) } }
        try { refresh.await();org.junit.Assert.fail("Cancelled refresh must propagate") } catch (_: CancellationException) { }
        assertEquals(original, file.readText())
        assertEquals(healthy, store.getOrCreate(library, now + 2) { error("Healthy memory must remain") })
        assertEquals(healthy, DailyRecommendationStore(file, lookupSongUuid = { null })
            .getOrCreate(library, now + 3) { error("Healthy disk must remain") })
    }

    @Test fun `empty or fully removed daily recommendations refill only when eligible library items exist`() = runTest {
        val file = File(temporary.root, "depleted.json")
        val store = DailyRecommendationStore(file, lookupSongUuid = { null })
        store.getOrCreate(emptyList(), now, forceRefresh = true) { LocalRecommendations() }
        val first = listOf(candidate("a", "First"))
        val populated = store.getOrCreate(first, now + 1) { recommendations(first) }
        assertEquals(listOf("a"), populated.items.map { it.identityKey })
        val replacement = listOf(candidate("b", "Replacement"))
        val refilled = store.getOrCreate(replacement, now + 2) { recommendations(replacement) }
        assertEquals(listOf("b"), refilled.items.map { it.identityKey })
        assertEquals(refilled, DailyRecommendationStore(file, lookupSongUuid = { null })
            .getOrCreate(replacement, now + 3) { error("Refilled cache stays stable after reopening") })
        val retired = candidate("retired", "Retired").let { it.copy(song = it.song.copy(channelId = "qqmusic")) }
        val empty = store.getOrCreate(listOf(retired), now + 4) { error("Retired-only input must not cause a refill loop") }
        assertTrue(empty.items.isEmpty())
    }

    @Test fun `new local files fill an empty offline mode without reshuffling valid library recommendations`() = runTest {
        val file = File(temporary.root, "offline-mode.json")
        val remote = candidate("remote", "Remote")
        val local = candidate("local", "Local", local = true)
        val store = DailyRecommendationStore(file, lookupSongUuid = { null })
        val original = store.getOrCreate(listOf(remote), now) { recommendations(listOf(remote)) }
        val updated = store.getOrCreate(listOf(local, remote), now + 1) { recommendations(listOf(local, remote)) }
        assertEquals(original.items, updated.items)
        assertEquals(listOf("local"), updated.localFileItems.map { it.identityKey })
        val reopened = DailyRecommendationStore(file, lookupSongUuid = { null })
        assertEquals(updated, reopened.getOrCreate(listOf(remote, local), now + 2) { error("Both modes now populated") })
        val nextLocal = candidate("next-local", "Replacement local", local = true)
        val replaced = reopened.getOrCreate(listOf(remote, nextLocal), now + 3) { recommendations(listOf(nextLocal, remote)) }
        assertEquals(original.items, replaced.items)
        assertEquals(listOf("next-local"), replaced.localFileItems.map { it.identityKey })
    }

    private fun candidate(key: String, title: String, local: Boolean = false): LocalRecommendationCandidate = LocalRecommendationCandidate(
        SongItem(key.hashCode().toLong(), title, "Artist", "album", 0, 180_000, "https://private.invalid/cover",
            localFilePath = if (local) "/private/music.flac" else null,
            streamUrl = "https://private.invalid/play?token=secret"),
        key, if (local) LocalRecommendationSource.LOCAL_FILE else LocalRecommendationSource.NETEASE
    )

    private fun recommendations(candidates: List<LocalRecommendationCandidate>) = LocalRecommendations(
        items = candidates.map { LocalRecommendation(it.song, it.identityKey, LocalRecommendationReason.EXPLORE_LIBRARY, it.source) },
        localFileItems = candidates.filter { it.source == LocalRecommendationSource.LOCAL_FILE }
            .map { LocalRecommendation(it.song, it.identityKey, LocalRecommendationReason.EXPLORE_LIBRARY, it.source) }
    )
}
