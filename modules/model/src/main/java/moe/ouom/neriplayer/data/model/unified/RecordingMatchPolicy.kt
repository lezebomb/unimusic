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
    // Java's Unicode word boundary does not split "Live版" or "Mash Up版".
    private fun englishVersion(pattern: String) = "(?<![A-Za-z0-9])(?:$pattern)(?![A-Za-z0-9])"
    private val artistCreditSeparators = Regex("[/&、;]")
    // Exact metadata sentinels, not substrings: e.g. Unknown Mortal Orchestra is a real credit.
    private val unknownArtistCredits = setOf(
        "unknown", "unknownartist", "unknownsinger", "未知", "未知歌手",
        "未知艺术家", "未知藝術家", "缺少歌手信息", "artistinformationmissing"
    )
    private val syntheticVoice = Regex(
        """(?<![A-Za-z0-9])ai(?![A-Za-z0-9])[^;\n|]{0,32}(?:翻唱|生成|合成|换声|音色|声线|歌声|人声)|人工智能[^;\n|]{0,16}(?:合成|生成|人声|歌声|翻唱)|声音克隆|人声合成|声线克隆|(?<![A-Za-z0-9])(?:ai[- ]+(?:generated|vocals?|voice|sung|cover(?:ed)?)|voice clon(?:e|ed|ing)|voice conversion)(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE
    )
    private val markers = linkedMapOf(
        RecordingVersion.LIVE to Regex("现场|演唱会|" + englishVersion("live"), RegexOption.IGNORE_CASE),
        RecordingVersion.COVER to Regex("翻唱|翻弹|翻奏|翻彈|弹唱|彈唱|清唱|カバー|歌ってみた|弾いてみた|弾き語り|" + englishVersion("cover(?:ed)?"), RegexOption.IGNORE_CASE),
        RecordingVersion.INSTRUMENTAL to Regex("伴奏|纯音乐|无人声|カラオケ|オフボーカル|インストゥルメンタル|" + englishVersion("instrumental|karaoke|backing track|off[\\s_-]*vocals?"), RegexOption.IGNORE_CASE),
        RecordingVersion.REMIX to Regex("混音|DJ\\s*版|" + englishVersion("remix(?:ed)?"), RegexOption.IGNORE_CASE),
        RecordingVersion.EDIT to Regex("剪辑|片段|串烧|混剪|教学|教程|讲解|乐理分析|" + englishVersion("edit|snippet|medley|mash[\\s_-]*up|short ver(?:sion)?|tutorial|lesson|reaction|how to play"), RegexOption.IGNORE_CASE),
        RecordingVersion.SPEED_ALTERED to Regex("加速|减速|变速|升调|降调|倍速|" + englishVersion("sped up|speed up|slowed|nightcore|pitch(?:ed)?"), RegexOption.IGNORE_CASE),
        RecordingVersion.REMASTER to Regex("重制|修复版|" + englishVersion("remaster(?:ed)?"), RegexOption.IGNORE_CASE),
        RecordingVersion.RERECORDING to Regex("重录|再录|" + englishVersion("re-record(?:ed|ing)?|rerecord(?:ed|ing)?|taylor['’]s version"), RegexOption.IGNORE_CASE)
    )
    private val brackets = Regex("[\\[（(【][^\\]）)】]*[\\]）)】]")
    private val alternateRemix = Regex(englishVersion("bootleg|rework(?:ed)?"), RegexOption.IGNORE_CASE)
    // Ambiguous as a song title: require an explicit bracketed producer collaboration, or version context.
    private val producerBooty = Regex("""[\p{L}\p{N}][\p{L}\p{N}\s.'’_-]{0,80}\s+(?:x|&|vs\.?)\s+[\p{L}\p{N}][\p{L}\p{N}\s.'’_-]{0,80}\s+booty""", RegexOption.IGNORE_CASE)
    private val decorations = Regex("^(?:hi[ -]?res|flac|wav|mp3|4k|1080p|mv|official(?: music)? video|official audio|lyrics?|无损(?:音质)?|高音质|高清|原版|官方(?:音频|mv)?|歌词(?:版)?|备份)$", RegexOption.IGNORE_CASE)

    private val versionSuffix = Regex("(?:\\s*[-–—|:]\\s*|\\s+)(?:live|cover|instrumental|remix|remastered?|radio edit|sped up|slowed)\\s*$", RegexOption.IGNORE_CASE)

    fun versions(metadata: RecordingMetadata): Set<RecordingVersion> = buildSet {
        if (metadata.version != RecordingVersion.UNKNOWN && metadata.version != RecordingVersion.STUDIO) add(metadata.version)
        val title = Normalizer.normalize(metadata.title, Normalizer.Form.NFKC)
        val context = Normalizer.normalize(metadata.versionContext, Normalizer.Form.NFKC)
        val text = "$title $context"
        markers.forEach { (version, regex) -> if (regex.containsMatchIn(text)) add(version) }
        if (alternateRemix.containsMatchIn(text) ||
            brackets.findAll(title).any { producerBooty.matches(it.value.drop(1).dropLast(1).trim()) } ||
            producerBooty.matches(context.trim())) add(RecordingVersion.REMIX)
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
            (versionSuffix.find(title)?.value ?: "")
        return versions(metadata.copy(title = labels))
    }

    fun assess(expected: RecordingMetadata, candidate: RecordingMetadata, toleranceMs: Long = 4_000): RecordingMatch {
        require(toleranceMs >= 0)
        if (hasVersionConflict(expected, candidate)) return RecordingMatch(false, RecordingMismatch.VERSION)
        val title = canonicalTitle(expected.title)
        val artist = canonicalArtist(expected.artist)
        val candidateArtist = canonicalArtist(candidate.artist)
        if (title.isEmpty() || artist.isEmpty() || candidateArtist.isEmpty() ||
            expected.durationMs <= 0 || candidate.durationMs <= 0) {
            return RecordingMatch(false, RecordingMismatch.MISSING_METADATA)
        }
        if (canonicalTitle(candidate.title) != title) return RecordingMatch(false, RecordingMismatch.TITLE)
        if (candidateArtist != artist) return RecordingMatch(false, RecordingMismatch.ARTIST)
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
        val credits = artistCredits(value)
        if (credits.any(String::isEmpty)) return ""
        return credits.sorted().joinToString("|") { "${it.length}:$it" }
    }

    /** Canonical credits; absent/placeholder credits remain empty evidence, including in collaborations. */
    fun artistCredits(value: String): List<String> = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .split(artistCreditSeparators).map(::canonicalText).map { if (it in unknownArtistCredits) "" else it }

    fun canonicalText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
}
