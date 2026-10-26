package moe.ouom.neriplayer.data.recommendation

import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.time.Instant
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
        assertEquals(setOf("schemaVersion", "day", "algorithmVersion", "modes"), JsonParser.parseString(json).asJsonObject.keySet())
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
