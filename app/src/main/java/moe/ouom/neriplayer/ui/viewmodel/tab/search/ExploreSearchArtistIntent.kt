package moe.ouom.neriplayer.ui.viewmodel.tab.search

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.RecordingMatchPolicy
import moe.ouom.neriplayer.data.model.unified.RecordingMetadata
import moe.ouom.neriplayer.data.model.unified.MusicSourceRegistry
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.ui.viewmodel.tab.ExploreSearchResult
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseSearchArtistResult

/** Query relevance only. Neither a search entity nor an artist credit certifies an original recording. */
internal data class SearchArtistIntent(val artists: List<NeteaseSearchArtistResult>,
    private val recordings: List<RecordingMetadata>) {
    private val ids = artists.map { it.artist.id }.toSet()
    private val names = artists.flatMap { listOf(it.artist.name) + it.aliases }
        .map(RecordingMatchPolicy::canonicalArtist).filter(String::isNotEmpty).toSet()
    private val recordingsByTitle = recordings.groupBy { RecordingMatchPolicy.canonicalTitle(it.title) }

    fun tier(item: ExploreSearchResult): Int = when (item) {
        is ExploreSearchResult.Artist -> if (item.result.artist.id in ids) 0 else 2
        is ExploreSearchResult.Song -> if (item.song.channelId == "netease" &&
            item.song.neteaseArtists.orEmpty().any { it.id in ids }) 1 else 2
        is ExploreSearchResult.Catalog -> {
            val metadata = item.track.metadata
            val candidate = RecordingMetadata(metadata.title, metadata.artist, metadata.durationMs, metadata.version, metadata.album)
            if (RecordingMatchPolicy.canonicalArtist(metadata.artist) in names &&
                recordingsByTitle[RecordingMatchPolicy.canonicalTitle(metadata.title)].orEmpty()
                    .any { RecordingMatchPolicy.assess(it, candidate).accepted }) 1 else 2
        }
        else -> 2
    }

    fun cards(): List<ExploreSearchResult> = artists.map(ExploreSearchResult::Artist)
}

/** Require either an exact name/alias or agreement between independent artist and song indexes. */
internal fun inferSearchArtistIntent(query: String, providerSongs: List<SongItem>,
    artists: List<NeteaseSearchArtistResult>): SearchArtistIntent? {
    val key = RecordingMatchPolicy.canonicalText(query)
    if (key.length < 2 || artists.isEmpty()) return null
    val valid = artists.distinctBy { it.artist.id }.filter { it.artist.id > 0 && it.artist.name.isNotBlank() }
    fun inferred(entities: List<NeteaseSearchArtistResult>): SearchArtistIntent {
        val ids = entities.map { it.artist.id }.toSet()
        val recordings = providerSongs.asSequence().filter { song -> neteaseArtistEvidenceKey(song) != null &&
            song.neteaseArtists.orEmpty().any { it.id in ids } }.distinctBy(::neteaseArtistEvidenceKey).take(64)
            .map { RecordingMetadata(it.name, it.artist, it.durationMs, versionContext = it.album) }.toList()
        return SearchArtistIntent(entities, recordings)
    }
    // A literal song-title interpretation remains ambiguous; keep its songs ahead of an inferred entity.
    if (providerSongs.take(6).any { RecordingMatchPolicy.canonicalTitle(it.name) == key }) return null
    val exact = valid.filter { entity -> (listOf(entity.artist.name) + entity.aliases)
        .any { RecordingMatchPolicy.canonicalText(it) == key } }
    if (exact.size == 1) return inferred(exact)
    if (exact.isNotEmpty() || valid.size !in 1..3 ||
        RecordingMatchPolicy.versions(RecordingMetadata(query, "", 0)).isNotEmpty()) return null
    // Repeated rows are one recording, not independent support from the song index.
    val sample = providerSongs.take(6).filter { neteaseArtistEvidenceKey(it) != null }
        .distinctBy(::neteaseArtistEvidenceKey)
    if (sample.size < 4) return null
    val ids = valid.map { it.artist.id }.toSet()
    val hits = sample.count { song -> song.neteaseArtists.orEmpty().any { it.id in ids } }
    if (hits < 4 || hits * 3 < sample.size * 2) return null
    val supported = valid.filter { entity -> sample.any { song -> song.neteaseArtists.orEmpty().any { it.id == entity.artist.id } } }
    return supported.takeIf { it.isNotEmpty() }?.let(::inferred)
}

private fun neteaseArtistEvidenceKey(song: SongItem): String? {
    if (song.channelId != "netease") return null
    val id = song.audioId ?: song.id.toString()
    return id.takeIf { MusicSourceRegistry.validKey(SongSourceKey("netease", it)) }
}

private val resourcePromotion = Regex("""(?i)(网盘|资源分享|全集下载|\d+集全|磁力链接|下载链接)""")

internal fun searchPromotionPenalty(query: String, item: ExploreSearchResult): Int {
    if (resourcePromotion.containsMatchIn(query)) return 0
    val song = (item as? ExploreSearchResult.Song)?.song ?: return 0
    return if (song.channelId == "bilibili" && resourcePromotion.containsMatchIn(song.name)) 1 else 0
}
