package moe.ouom.neriplayer.data.recommendation

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

internal enum class DailyRecommendationMode { LIBRARY, LOCAL_FILES }

/** Deliberately excludes song payloads, raw legacy keys, local paths and remote URLs. */
internal data class DailyRecommendationEntry(
    val localKeyDigest: String,
    val songUuid: String?,
    val reason: LocalRecommendationReason,
    val source: LocalRecommendationSource
)

/** Device-only reset/consent context; contains no events, identities, paths or account information. */
data class RecommendationEvidenceContext(
    val statisticsClearedAt: Long = 0L,
    val feedbackClearedAt: Long = 0L,
    val feedbackEnabled: Boolean = true,
    val feedbackAvailable: Boolean = true
) {
    init { require(statisticsClearedAt >= 0L && feedbackClearedAt >= 0L) }
}

internal data class DailyRecommendationSnapshot(
    val day: String,
    val algorithmVersion: Int,
    val modes: Map<DailyRecommendationMode, List<DailyRecommendationEntry>>,
    val evidenceContext: RecommendationEvidenceContext = RecommendationEvidenceContext()
) {
    fun matches(nowMillis: Long, context: RecommendationEvidenceContext): Boolean =
        day == BeijingRecommendationDay.key(nowMillis) && algorithmVersion == LocalRecommendationRanker.ALGORITHM_VERSION &&
            evidenceContext == context
}

internal fun recommendationIdentityDigest(identityKey: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(identityKey.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 15])
        }
    }
}

internal object DailyRecommendationSnapshotCodec {
    const val MAX_BYTES = 32 * 1024
    private const val SCHEMA_VERSION = 1
    private const val MAX_ITEMS_PER_MODE = 12
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun encode(snapshot: DailyRecommendationSnapshot): String = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        addProperty("day", snapshot.day)
        addProperty("algorithmVersion", snapshot.algorithmVersion)
        add("evidenceContext", JsonObject().apply {
            addProperty("statisticsClearedAt", snapshot.evidenceContext.statisticsClearedAt)
            addProperty("feedbackClearedAt", snapshot.evidenceContext.feedbackClearedAt)
            addProperty("feedbackEnabled", snapshot.evidenceContext.feedbackEnabled)
            addProperty("feedbackAvailable", snapshot.evidenceContext.feedbackAvailable)
        })
        add("modes", JsonArray().apply {
            DailyRecommendationMode.entries.forEach { mode ->
                add(JsonObject().apply {
                    addProperty("mode", mode.name)
                    add("items", JsonArray().apply {
                        snapshot.modes.getValue(mode).forEach { entry ->
                            add(JsonObject().apply {
                                addProperty("localKeyDigest", entry.localKeyDigest)
                                add("songUuid", entry.songUuid?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                                addProperty("reason", entry.reason.name)
                                addProperty("source", entry.source.name)
                            })
                        }
                    })
                })
            }
        })
    }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }

    fun decode(text: String): DailyRecommendationSnapshot {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JsonParser.parseString(text).asJsonObject
        require(root.integer("schemaVersion") == SCHEMA_VERSION)
        val day = root.text("day")
        require(LocalDate.parse(day).toString() == day)
        val modes = linkedMapOf<DailyRecommendationMode, List<DailyRecommendationEntry>>()
        val modeArray = root.getAsJsonArray("modes")
        require(modeArray.size() == DailyRecommendationMode.entries.size)
        modeArray.forEach { value ->
            val section = value.asJsonObject
            val mode = DailyRecommendationMode.valueOf(section.text("mode"))
            require(mode !in modes)
            val items = section.getAsJsonArray("items")
            require(items.size() <= MAX_ITEMS_PER_MODE)
            val entries = items.map { item ->
                val row = item.asJsonObject
                val digest = row.text("localKeyDigest")
                require(digestPattern.matches(digest))
                val uuid = row.get("songUuid").let { if (it.isJsonNull) null else row.text("songUuid") }
                if (uuid != null) require(UUID.fromString(uuid).toString() == uuid)
                DailyRecommendationEntry(
                    digest, uuid, LocalRecommendationReason.valueOf(row.text("reason")),
                    LocalRecommendationSource.valueOf(row.text("source"))
                ).also { entry ->
                    require(mode != DailyRecommendationMode.LOCAL_FILES || entry.source == LocalRecommendationSource.LOCAL_FILE)
                }
            }
            require(entries.map { it.localKeyDigest }.distinct().size == entries.size)
            modes[mode] = entries
        }
        // Legacy v1 caches are reusable only for the untouched default evidence context.
        val context = if (!root.has("evidenceContext")) RecommendationEvidenceContext() else {
            val value = root.getAsJsonObject("evidenceContext")
            RecommendationEvidenceContext(value.nonnegativeLong("statisticsClearedAt"), value.nonnegativeLong("feedbackClearedAt"),
                value.boolean("feedbackEnabled"), value.boolean("feedbackAvailable"))
        }
        return DailyRecommendationSnapshot(day, root.integer("algorithmVersion"), modes, context)
    }

    private fun JsonObject.nonnegativeLong(name: String): Long {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return requireNotNull(value.asString.toLongOrNull()).also { require(it >= 0L) }
    }

    private fun JsonObject.boolean(name: String): Boolean {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        return value.asBoolean
    }

    private fun JsonObject.text(name: String): String {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }

    private fun JsonObject.integer(name: String): Int {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return requireNotNull(value.asString.toIntOrNull())
    }
}
