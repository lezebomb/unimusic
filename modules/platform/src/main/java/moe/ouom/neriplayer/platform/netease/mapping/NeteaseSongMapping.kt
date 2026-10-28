package moe.ouom.neriplayer.platform.netease.mapping

import moe.ouom.neriplayer.data.model.unified.RecordingMatchPolicy
import moe.ouom.neriplayer.data.model.unified.RecordingMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.json.mapObjectsNotNull
import org.json.JSONArray
import org.json.JSONObject

fun parseNeteaseSearchSongs(raw: String): List<SongItem> = parseNeteaseSearchSongs(JSONObject(raw))

/** Read-only root overload avoids reparsing a response already inspected by the caller. */
fun parseNeteaseSearchSongs(root: JSONObject): List<SongItem> {
    if (root.optInt("code", -1) != 200) return emptyList()

    val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
    return songs.mapObjectsNotNull { row ->
        parseNeteaseSong(row)?.let { it.copy(name = searchTitleWithVersionAliases(it.name, row)) }
    }
}

fun parseNeteaseSongDetail(raw: String): SongItem? {
    val root = JSONObject(raw)
    if (root.optInt("code", -1) != 200) return null
    val songs = root.optJSONArray("songs") ?: return null
    return parseNeteaseSongArray(songs).firstOrNull()
}

private fun parseNeteaseSongArray(songs: JSONArray): List<SongItem> {
    return songs.mapObjectsNotNull(::parseNeteaseSong)
}

private fun parseNeteaseSong(song: JSONObject): SongItem? {
    val songId = song.optLong("id", 0L)
    val name = song.optString("name", "")
    if (songId <= 0L || name.isBlank()) return null

    val artistItems = parseNeteaseArtistsFromSongJson(song)
    val album = song.optJSONObject("al") ?: song.optJSONObject("album")
    val albumName = album?.optString("name", "").orEmpty()
    return SongItem(
        id = songId,
        name = name,
        artist = artistItems.joinToString(" / ") { it.name },
        album = albumName,
        albumId = album?.optLong("id", 0L) ?: 0L,
        durationMs = song.optLong("dt", 0L),
        coverUrl = parseNeteaseAlbumCover(album),
        channelId = "netease",
        audioId = songId.toString(),
        neteaseArtists = artistItems
    )
}

/** Keep explicit version evidence visible without replacing the song title with ordinary translations. */
private fun searchTitleWithVersionAliases(title: String, row: JSONObject): String {
    val canonical = RecordingMatchPolicy.canonicalText(title)
    val clues = buildList {
        for (key in listOf("alia", "alias", "tns", "transNames")) {
            val values = row.optJSONArray(key) ?: continue
            for (index in 0 until values.length()) {
                val alias = (values.opt(index) as? String)?.trim()?.takeIf(String::isNotEmpty) ?: continue
                if (RecordingMatchPolicy.canonicalText(alias) != canonical &&
                    RecordingMatchPolicy.versions(RecordingMetadata("", "", 0, versionContext = alias)).isNotEmpty()) add(alias)
            }
        }
    }.distinct()
    return if (clues.isEmpty()) title else "$title (${clues.joinToString(" · ")})"
}

private fun parseNeteaseAlbumCover(album: JSONObject?): String? {
    return album?.optString("picUrl", "")
        ?.replaceFirst("http://", "https://")
        ?.takeIf { it.isNotBlank() }
}
