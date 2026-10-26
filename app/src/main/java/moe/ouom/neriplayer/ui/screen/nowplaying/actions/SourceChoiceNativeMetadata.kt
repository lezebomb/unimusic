package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import java.io.IOException
import moe.ouom.neriplayer.data.model.unified.RecordingMatchPolicy
import moe.ouom.neriplayer.data.model.unified.RecordingMetadata
import moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate
import moe.ouom.neriplayer.data.model.unified.RecordingVersion
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResult
import moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseSearchSongs
import org.json.JSONObject

internal class SourceChoiceAuthRequiredException : IOException("Source login required")

/** Preserve native IDs and version evidence; never substitute query text for missing metadata. */
internal fun parseSourceChoiceNetease(raw: String): List<RecordingSourceCandidate> {
    val root = JSONObject(raw)
    val code = root.optInt("code", -1)
    if (code == 301) throw SourceChoiceAuthRequiredException()
    if (code != 200) throw IOException("NetEase metadata search failed: code=$code")
    val songs = root.optJSONObject("result")?.optJSONArray("songs")
    val nativeRows = buildMap {
        if (songs != null) for (index in 0 until songs.length()) {
            val row = songs.optJSONObject(index) ?: continue
            val rawId = row.opt("id")?.toString().orEmpty()
            val id = rawId.toLongOrNull()?.takeIf { it > 0 && it.toString() == rawId } ?: continue
            put(id, row)
        }
    }
    return parseNeteaseSearchSongs(raw).mapNotNull { song ->
        val row = nativeRows[song.id] ?: return@mapNotNull null
        val aliases = listOf("alia", "alias", "tns", "transNames").flatMap { key ->
            val array = row.optJSONArray(key) ?: return@flatMap emptyList()
            (0 until array.length()).mapNotNull { array.opt(it) as? String }
        }
        val context = (listOf(song.album) + aliases).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        RecordingSourceCandidate(SongSourceKey("netease", song.id.toString()),
            nativeSourceMetadata(song.name, song.artist, context, song.durationMs))
    }.distinctBy { it.key }
}

internal fun sourceChoiceYouTube(result: YouTubeMusicSearchResult): RecordingSourceCandidate? {
    if (!result.videoId.matches(Regex("[A-Za-z0-9_-]{11}")) || result.title.isBlank()) return null
    val context = listOf(result.album, result.subtitle).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    return RecordingSourceCandidate(SongSourceKey("youtube_music", result.videoId),
        nativeSourceMetadata(result.title, result.artist, context, result.durationMs))
}

private fun nativeSourceMetadata(title: String, artist: String, context: String, durationMs: Long): UnifiedSongMetadata {
    val duration = durationMs.coerceAtLeast(0L)
    val metadata = RecordingMetadata(title, artist, duration, versionContext = context)
    return UnifiedSongMetadata(title, artist, context, duration,
        RecordingMatchPolicy.versions(metadata).singleOrNull() ?: RecordingVersion.UNKNOWN)
}
