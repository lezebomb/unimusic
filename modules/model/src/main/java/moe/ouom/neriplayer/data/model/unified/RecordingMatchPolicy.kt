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
    private val artistCreditSeparators = Regex("[/&、;]")
    private val syntheticVoice = Regex(
        """(?<![A-Za-z0-9])ai(?![A-Za-z0-9])[^;\n|]{0,32}(?:翻唱|生成|合成|换声|音色|声线|歌声|人声)|人工智能[^;\n|]{0,16}(?:合成|生成|人声|歌声|翻唱)|声音克隆|人声合成|声线克隆|\b(?:ai[- ]+(?:generated|vocals?|voice|sung|cover(?:ed)?)|voice clon(?:e|ed|ing)|voice conversion)\b""",
        RegexOption.IGNORE_CASE
    )
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

    private val versionSuffix = Regex("(?:\\s*[-–—|:]\\s*|\\s+)(?:live|cover|instrumental|remix|remastered?|radio edit|sped up|slowed)\\s*$", RegexOption.IGNORE_CASE)

    fun versions(metadata: RecordingMetadata): Set<RecordingVersion> = buildSet {
        if (metadata.version != RecordingVersion.UNKNOWN && metadata.version != RecordingVersion.STUDIO) add(metadata.version)
        val text = Normalizer.normalize("${metadata.title} ${metadata.versionContext}", Normalizer.Form.NFKC)
        markers.forEach { (version, regex) -> if (regex.containsMatchIn(text)) add(version) }
        if (syntheticVoice.containsMatchIn(text)) add(RecordingVersion.COVER)
    }

    fun hasVersionConflict(expected: RecordingMetadata, candidate: RecordingMetadata): Boolean {
        if (versions(expected) != versions(candidate)) return true
        // Synthetic vocals also differ from an ordinary human cover; both can have COVER evidence.
        if (hasSyntheticVoice(expected) != hasSyntheticVoice(candidate)) return true
        // A word in the song's actual title must not mask an added explicit version label.
        return taggedVersions(expected) != taggedVersions(candidate)
    }

    private fun hasSyntheticVoice(metadata: RecordingMetadata): Boolean = syntheticVoice.containsMatchIn(
        Normalizer.normalize("${metadata.title} ${metadata.versionContext}", Normalizer.Form.NFKC))

    private fun taggedVersions(metadata: RecordingMetadata): Set<RecordingVersion> {
        val title = Normalizer.normalize(metadata.title, Normalizer.Form.NFKC)
        val labels = brackets.findAll(title).joinToString(" ") { it.value } + " " +
            (versionSuffix.find(title)?.value ?: "") + " " + metadata.versionContext
        return versions(metadata.copy(title = labels, versionContext = ""))
    }

    fun assess(expected: RecordingMetadata, candidate: RecordingMetadata, toleranceMs: Long = 4_000): RecordingMatch {
        require(toleranceMs >= 0)
        if (hasVersionConflict(expected, candidate)) return RecordingMatch(false, RecordingMismatch.VERSION)
        val title = canonicalTitle(expected.title)
        val artist = canonicalArtist(expected.artist)
        if (title.isEmpty() || artist.isEmpty() || expected.durationMs <= 0 || candidate.durationMs <= 0) {
            return RecordingMatch(false, RecordingMismatch.MISSING_METADATA)
        }
        if (canonicalTitle(candidate.title) != title) return RecordingMatch(false, RecordingMismatch.TITLE)
        if (canonicalArtist(candidate.artist) != artist) return RecordingMatch(false, RecordingMismatch.ARTIST)
        if (abs(expected.durationMs - candidate.durationMs) > toleranceMs) return RecordingMatch(false, RecordingMismatch.DURATION)
        return RecordingMatch(true)
    }

    fun canonicalTitle(title: String): String {
        var text = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
        text = brackets.replace(text) { match ->
            val inner = match.value.drop(1).dropLast(1).trim()
            if (decorations.matches(inner) || markers.values.any { it.containsMatchIn(inner) } ||
                syntheticVoice.containsMatchIn(inner)) " " else match.value
        }
        text = text.replace(versionSuffix, "")
        return canonicalText(text)
    }

    /** Preserve credit boundaries: A/B is not AB. Unknown aliases/band spellings require manual choice. */
    fun canonicalArtist(value: String): String {
        val credits = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .split(artistCreditSeparators).map(::canonicalText)
        if (credits.any(String::isEmpty)) return ""
        return credits.sorted().joinToString("|") { "${it.length}:$it" }
    }

    fun canonicalText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
}
