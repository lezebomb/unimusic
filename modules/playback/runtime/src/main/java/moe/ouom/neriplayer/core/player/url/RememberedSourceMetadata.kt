package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.policy.source.RecordingMetadata
import moe.ouom.neriplayer.core.player.policy.source.SourceFailure
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseSongDetail
import org.json.JSONObject

internal fun neteaseDetailsAccessFailure(raw: String): SourceFailure? = when (JSONObject(raw).optInt("code", -1)) {
    200 -> null
    301 -> SourceFailure.AUTH_REQUIRED
    else -> SourceFailure.UNAVAILABLE
}

/** Titles alone can hide Live/edit tags supplied in aliases or translated release names. */
internal fun refreshedNeteaseSource(raw: String, resourceId: String): Pair<SongItem, RecordingMetadata>? {
    val root = JSONObject(raw)
    val row = root.optJSONArray("songs")?.optJSONObject(0) ?: return null
    if (row.opt("id")?.toString() != resourceId) return null
    val song = parseNeteaseSongDetail(raw)?.takeIf { it.id.toString() == resourceId } ?: return null
    val context = listOf(song.album) + listOf("alia", "alias", "tns", "transNames").flatMap { key ->
        val array = row.optJSONArray(key) ?: return@flatMap emptyList()
        (0 until array.length()).mapNotNull { array.opt(it) as? String }
    }
    return song to RecordingMetadata(song.name, song.artist, song.durationMs,
        versionContext = context.filter(String::isNotBlank).distinct().joinToString(" · "))
}
