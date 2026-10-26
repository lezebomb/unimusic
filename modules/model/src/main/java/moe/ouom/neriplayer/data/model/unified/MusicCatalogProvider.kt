package moe.ouom.neriplayer.data.model.unified

/** Catalog discovery is independent of the ability to obtain or play audio. */
data class CatalogTrack(
    val source: SongSourceKey,
    val metadata: UnifiedSongMetadata,
    val externalPageUrl: String?,
    val playbackMode: SourcePlaybackMode = SourcePlaybackMode.METADATA_ONLY
)

data class CatalogSearchPage(
    val tracks: List<CatalogTrack>,
    val nextCursor: String?,
    val totalCount: Long?
)

interface MusicCatalogProvider {
    val providerId: String
    val playbackMode: SourcePlaybackMode
    suspend fun search(query: String, cursor: String? = null, limit: Int = 20): CatalogSearchPage
}
