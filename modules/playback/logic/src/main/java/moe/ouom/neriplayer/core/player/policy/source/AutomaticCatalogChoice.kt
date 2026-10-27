package moe.ouom.neriplayer.core.player.policy.source

import moe.ouom.neriplayer.data.model.unified.*

/** An exact album may disambiguate duplicate IDs; search order and popularity never do. */
fun automaticCatalogChoice(expected: RecordingMetadata, tracks: List<CatalogTrack>, original: SongSourceKey?): RecordingSourceCandidate? {
    val qualified = tracks.distinctBy { it.source }.filter { track ->
        track.source != original && MusicSourceRegistry.canResolveAudio(track.source) &&
            track.metadata.let { RecordingMatchPolicy.assess(expected,
                RecordingMetadata(it.title, it.artist, it.durationMs, it.version, it.album)).accepted }
    }
    val album = RecordingMatchPolicy.canonicalText(expected.versionContext)
    val exactAlbum = if (album.isBlank()) emptyList() else qualified.filter {
        RecordingMatchPolicy.canonicalText(it.metadata.album) == album }
    val track = exactAlbum.singleOrNull() ?: qualified.singleOrNull() ?: return null
    return RecordingSourceCandidate(track.source, track.metadata)
}
