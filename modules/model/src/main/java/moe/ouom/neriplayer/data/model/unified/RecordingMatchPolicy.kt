package moe.ouom.neriplayer.data.model.unified

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class RecordingMetadata(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val version: RecordingVersion = RecordingVersion.UNKNOWN,
    val versionContext: String = ""
)

enum class RecordingMismatch { VERSION, TITLE, ARTIST, DURATION, MISSING_METADATA }
data class RecordingMatch(val accepted: Boolean, val reason: RecordingMismatch? = null)

/** Conservative metadata gate, not a calibrated recording-identity probability. */
object RecordingMatchPolicy {
    private val markers = linkedMapOf(
        RecordingVersion.LIVE to Regex("现场|演唱会|\\blive\\b", RegexOption.IGNORE_CASE),
        RecordingVersion.COVER to Regex("翻唱|翻弹|翻奏|\\bcover(?:ed)?\\b", RegexOption.IGNORE_CASE),
        RecordingVersion.INSTRUMENTAL to Regex("伴奏|纯音乐|无人声|\\b(?:instrumental|karaoke|backing track)\\b", RegexOption.IGNORE_CASE),
        RecordingVersion.REMIX to Regex("混音|\\bremix(?:ed)?\\b", RegexOption.IGNORE_CASE),
        RecordingVersion.EDIT to Regex("剪辑|片段|串烧|混剪|教学|教程|讲解|乐理分析|\\b(?:edit|snippet|medley|mashup|short ver(?:sion)?|tutorial|lesson|reaction|how to play)\\b", RegexOption.IGNORE_CASE),
        RecordingVersion.SPEED_ALTERED to Regex("加速|减速|变速|升调|降调|倍速|\\b(?:sped up|speed up|slowed|nightcore|pitch(?:ed)?)\\b", RegexOption.IGNORE_CASE),
        RecordingVersion.REMASTER to Regex("重制|修复版|\\bremaster(?:ed)?\\b", RegexOption.IGNORE_CASE),
        RecordingVersion.RERECORDING to Regex("重录|再录|\\b(?:re-record(?:ed|ing)?|rerecord(?:ed|ing)?|taylor['’]s version)\\b", RegexOption.IGNORE_CASE)
    )
    private val brackets = Regex("[\\[（(【][^\\]）)】]*[\\]）)】]")
    private val decorations = Regex("^(?:hi[ -]?res|flac|wav|mp3|4k|1080p|mv|official(?: music)? video|official audio|lyrics?|无损(?:音质)?|高音质|高清|原版|官方(?:音频|mv)?|歌词(?:版)?|备份)$", RegexOption.IGNORE_CASE)

    fun versions(metadata: RecordingMetadata): Set<RecordingVersion> = buildSet {
        if (metadata.version != RecordingVersion.UNKNOWN && metadata.version != RecordingVersion.STUDIO) add(metadata.version)
        val text = Normalizer.normalize("${metadata.title} ${metadata.versionContext}", Normalizer.Form.NFKC)
        markers.forEach { (version, regex) -> if (regex.containsMatchIn(text)) add(version) }
    }

    fun hasVersionConflict(expected: RecordingMetadata, candidate: RecordingMetadata): Boolean =
        versions(expected) != versions(candidate)

    fun assess(expected: RecordingMetadata, candidate: RecordingMetadata, toleranceMs: Long = 4_000): RecordingMatch {
        require(toleranceMs >= 0)
        if (hasVersionConflict(expected, candidate)) return RecordingMatch(false, RecordingMismatch.VERSION)
        val title = canonicalTitle(expected.title)
        val artist = canonicalText(expected.artist)
        if (title.isEmpty() || artist.isEmpty() || expected.durationMs <= 0 || candidate.durationMs <= 0) {
            return RecordingMatch(false, RecordingMismatch.MISSING_METADATA)
        }
        if (canonicalTitle(candidate.title) != title) return RecordingMatch(false, RecordingMismatch.TITLE)
        if (canonicalText(candidate.artist) != artist) return RecordingMatch(false, RecordingMismatch.ARTIST)
        if (abs(expected.durationMs - candidate.durationMs) > toleranceMs) return RecordingMatch(false, RecordingMismatch.DURATION)
        return RecordingMatch(true)
    }

    fun canonicalTitle(title: String): String {
        var text = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
        text = brackets.replace(text) { match ->
            val inner = match.value.drop(1).dropLast(1).trim()
            if (decorations.matches(inner) || markers.values.any { it.containsMatchIn(inner) }) " " else match.value
        }
        text = text.replace(Regex("(?:\\s*[-–—|:]\\s*|\\s+)(?:live|cover|instrumental|remix|remastered?|radio edit|sped up|slowed)\\s*$", RegexOption.IGNORE_CASE), "")
        return canonicalText(text)
    }

    fun canonicalText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
}
