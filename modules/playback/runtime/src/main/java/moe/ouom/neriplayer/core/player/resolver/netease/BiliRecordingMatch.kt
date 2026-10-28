package moe.ouom.neriplayer.core.player.resolver.netease

import java.text.Normalizer
import moe.ouom.neriplayer.core.player.policy.source.RecordingMatchPolicy
import moe.ouom.neriplayer.core.player.policy.source.RecordingMetadata
import moe.ouom.neriplayer.core.player.policy.source.SourceFailure
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.playback.PlayInfo

internal data class BiliPageSelection(val page: VideoPage?, val rejection: SourceFailure?)

/** A name match is corroborating text, never proof that the account is verified or owns a recording. */
internal fun hasBiliUploaderArtistEvidence(artist: String, uploader: String): Boolean {
    val expected = RecordingMatchPolicy.canonicalArtist(artist)
    return expected.isNotEmpty() && expected == RecordingMatchPolicy.canonicalArtist(uploader)
}

internal fun biliAutoSourceAccessFailure(info: PlayInfo, expectedDurationMs: Long): SourceFailure? {
    val data = info.raw.optJSONObject("data") ?: info.raw
    if (data.optBoolean("is_preview", false) || data.optInt("is_preview", 0) == 1) return SourceFailure.PREVIEW_ONLY
    if (data.optBoolean("is_drm", false) || data.optInt("is_drm", 0) == 1 || data.optInt("drm_type", 0) > 0) {
        return SourceFailure.EXTERNAL_PLAYER_REQUIRED
    }
    val duration = info.timeLengthMs?.takeIf { it > 0 } ?: return SourceFailure.UNAVAILABLE
    if (expectedDurationMs <= 0 || kotlin.math.abs(duration - expectedDurationMs) > 4_000) return SourceFailure.PREVIEW_ONLY
    return null
}

internal fun selectStrictBiliPage(song: SongItem, info: VideoBasicInfo, searchTitle: String, selectedCid: Long? = null): BiliPageSelection {
    val expected = song.recordingMetadata()
    val pages = info.pages.filter { page ->
        if (selectedCid != null && page.cid != selectedCid) return@filter false
        if (page.cid <= 0) return@filter false
        val genericPart = page.part.trim().matches(Regex("(?i)(?:p?1|正片|视频|音频|默认|video|audio)"))
        val title = if (info.pages.size == 1 && genericPart) info.title else page.part
        val context = "$searchTitle ${info.title} ${page.part}"
        val candidate = biliRecordingMetadata(expected, title, context, info.ownerName,
            page.durationSec.takeIf { it > 0 }?.times(1000L)
                ?: if (info.pages.size == 1) info.durationSec * 1000L else 0)
        RecordingMatchPolicy.assess(expected, candidate).accepted
    }
    return when (pages.size) {
        1 -> BiliPageSelection(pages.single(), null)
        0 -> BiliPageSelection(null, SourceFailure.NO_MATCH)
        else -> BiliPageSelection(null, SourceFailure.AMBIGUOUS)
    }
}

fun SongItem.recordingMetadata() = RecordingMetadata(
    originalName ?: name, originalArtist ?: artist, durationMs, versionContext = album)

/** Uploader text is metadata evidence only. It never establishes a playback entitlement. */
fun biliRecordingMetadata(
    expected: RecordingMetadata, title: String, context: String, author: String, durationMs: Long
): RecordingMetadata {
    val artist = expected.artist.trim()
    if (artist.isBlank()) return RecordingMetadata(title, "", durationMs, versionContext = context)
    val artistPattern = if (artist.any { it.code in 0x41..0x7a }) {
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(artist)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    } else Regex(Regex.escape(artist), RegexOption.IGNORE_CASE)
    val artistPresent = artistPattern.containsMatchIn(context) ||
        RecordingMatchPolicy.canonicalArtist(author) == RecordingMatchPolicy.canonicalArtist(artist)
    var cleanTitle = Normalizer.normalize(title, Normalizer.Form.NFKC)
    // Only remove a real artist token; Sia must not be removed from the word Asia.
    cleanTitle = artistPattern.replace(cleanTitle, " ")
    cleanTitle = cleanTitle.replace(Regex("(?i)\\.(?:mp4|mkv|flac|mp3|wav|m4a)$"), "")
    val quality = "(?:无损音质|无损|高音质|高清|官方MV|官方音频|原版|Official Music Video|Official Audio|Music Video|MV|FLAC|Hi[ -]?Res|4K|1080P)"
    cleanTitle = cleanTitle.replace(Regex("(?i)^(?:\\s|[-|:：])*($quality(?:\\s|[-|:：])*)+"), "")
        .replace(Regex("(?i)(?:\\s|[-|:：])*($quality(?:\\s|[-|:：])*)+$"), "")
    return RecordingMetadata(cleanTitle, if (artistPresent) artist else "", durationMs, versionContext = context)
}
