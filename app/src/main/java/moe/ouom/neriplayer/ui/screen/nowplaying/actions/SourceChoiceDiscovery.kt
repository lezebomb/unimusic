package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.resolver.netease.biliPageRecordingMetadata
import moe.ouom.neriplayer.core.player.resolver.netease.recordingMetadata
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.unified.RecordingMatchPolicy
import moe.ouom.neriplayer.data.model.unified.RecordingMetadata
import moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate
import moe.ouom.neriplayer.data.model.unified.RecordingVersion
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchFilter
import moe.ouom.neriplayer.data.traffic.isOfflineModeNow
import moe.ouom.neriplayer.data.unified.sourceCandidateForSong
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureDisabledException
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate

internal enum class SourceChoiceFailure { TIMED_OUT, AUTH_REQUIRED, UNAVAILABLE }

internal data class SourceChoiceDiscoveryResult(
    val candidates: List<RecordingSourceCandidate>,
    val incomplete: Boolean = false,
    val offline: Boolean = false,
    val failures: Map<String, SourceChoiceFailure> = emptyMap()
)

internal fun assessSourceChoice(song: SongItem, candidate: RecordingSourceCandidate) =
    RecordingMatchPolicy.assess(song.recordingMetadata(), candidate.recordingMetadata(),
        if (candidate.key.provider == "local") 8_000 else 4_000)

private fun RecordingSourceCandidate.recordingMetadata() = metadata.let {
    RecordingMetadata(it.title, it.artist, it.durationMs, it.version, it.album)
}

/** Metadata discovery only: no audio URLs, unlock services, or entitlement changes. */
internal suspend fun discoverSourceChoices(
    context: Context, song: SongItem, youtubeEnabled: Boolean
): SourceChoiceDiscoveryResult =
    supervisorScope {
        val offline = context.isOfflineModeNow()
        val local = async { boundedCandidates("local") { discoverLocalChoices(context, song) } }
        val bili = async {
            if (offline) SourceChoiceDiscoveryResult(emptyList())
            else boundedCandidates("bilibili") { discoverBiliChoices(song) }
        }
        val netease = async {
            if (offline) SourceChoiceDiscoveryResult(emptyList())
            else boundedCandidates("netease") { discoverNeteaseChoices(song) }
        }
        val youtube = async {
            if (offline || !youtubeEnabled || !moe.ouom.neriplayer.data.model.unified.MusicSourceRegistry.isEnabled("youtube_music")) SourceChoiceDiscoveryResult(emptyList())
            else boundedCandidates("youtube_music") { discoverYouTubeChoices(song) }
        }
        val qq = async { if (offline || !moe.ouom.neriplayer.data.model.unified.MusicSourceRegistry.isEnabled("qqmusic")) SourceChoiceDiscoveryResult(emptyList()) else boundedCandidates("qqmusic") {
            val page = AppContainer.qqMusicAudioClient.search(song.recordingMetadata().title, null, 12)
            SourceChoiceDiscoveryResult(page.tracks.map { RecordingSourceCandidate(it.source, it.metadata) }) } }
        val kuwo = async { if (offline) SourceChoiceDiscoveryResult(emptyList()) else boundedCandidates("kuwo") {
            val page = AppContainer.kuwoCatalogProvider.search(song.recordingMetadata().title, null, 12)
            SourceChoiceDiscoveryResult(page.tracks.map { RecordingSourceCandidate(it.source, it.metadata) }) } }
        val results = listOf(local, bili, netease, youtube, qq, kuwo).awaitAll()
        val originalKey = sourceCandidateForSong(song)?.key
        // Only offer exact NetEase identities. Opening the sheet never calls the GD URL API.
        val gdChoices = ((sourceCandidateForSong(song)?.let(::listOf) ?: emptyList()) +
            results.flatMap { it.candidates }).filter { it.key.provider == "netease" }
            .map { it.copy(key = it.key.copy(provider = "gdstudio_netease")) }
        SourceChoiceDiscoveryResult(
            candidates = (gdChoices + results.flatMap { result -> result.candidates.filter { it.key != originalKey }
                .sortedByDescending { assessSourceChoice(song, it).accepted }.take(8) }
                ).distinctBy { it.key }.sortedByDescending { assessSourceChoice(song, it).accepted },
            incomplete = results.any { it.incomplete },
            offline = offline,
            failures = results.flatMap { it.failures.entries }.associate { it.toPair() }
        )
    }

private suspend fun boundedCandidates(
    provider: String,
    discover: suspend () -> SourceChoiceDiscoveryResult
): SourceChoiceDiscoveryResult = try {
    withTimeoutOrNull(8_000) { discover() }?.let { result ->
        if (result.incomplete) result.copy(failures = result.failures + (provider to SourceChoiceFailure.UNAVAILABLE))
        else result
    } ?: failedSource(provider, SourceChoiceFailure.TIMED_OUT)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: SourceChoiceAuthRequiredException) {
    failedSource(provider, SourceChoiceFailure.AUTH_REQUIRED)
} catch (_: Exception) {
    failedSource(provider, SourceChoiceFailure.UNAVAILABLE)
}

private fun failedSource(provider: String, reason: SourceChoiceFailure) = SourceChoiceDiscoveryResult(
    emptyList(), incomplete = true, failures = mapOf(provider to reason))

private fun sourceChoiceQuery(song: SongItem): String = song.recordingMetadata().let {
    listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" ")
}

private suspend fun discoverNeteaseChoices(song: SongItem): SourceChoiceDiscoveryResult {
    if (AppContainer.neteaseCookieRepo.getAuthHealthOnce().state == SavedCookieAuthState.Missing) {
        throw SourceChoiceAuthRequiredException()
    }
    val client = AppContainer.neteaseClient
    val session = client.searchSessionIdentity()
    val query = sourceChoiceQuery(song)
    if (query.isBlank()) return SourceChoiceDiscoveryResult(emptyList())
    val raw = client.searchSongsCancellable(query, limit = 12, type = 1, usePersistedCookies = true)
    if (session !== client.searchSessionIdentity()) throw SourceChoiceAuthRequiredException()
    return SourceChoiceDiscoveryResult(parseSourceChoiceNetease(raw))
}

private suspend fun discoverYouTubeChoices(song: SongItem): SourceChoiceDiscoveryResult {
    if (!YouTubeFeatureGate.isEnabled()) throw YouTubeFeatureDisabledException()
    val query = sourceChoiceQuery(song)
    if (query.isBlank()) return SourceChoiceDiscoveryResult(emptyList())
    val results = AppContainer.youtubeMusicClient.search(query, limit = 12, filter = YouTubeMusicSearchFilter.Song)
    if (!YouTubeFeatureGate.isEnabled()) throw YouTubeFeatureDisabledException()
    return SourceChoiceDiscoveryResult(results.mapNotNull(::sourceChoiceYouTube).distinctBy { it.key })
}

private suspend fun discoverLocalChoices(context: Context, song: SongItem): SourceChoiceDiscoveryResult =
    withContext(Dispatchers.IO) {
        val repository = LocalPlaylistRepository.getInstance(context)
        check(repository.awaitInitialized())
        val title = RecordingMatchPolicy.canonicalTitle(song.originalName ?: song.name)
        val candidates = repository.playlists.value.asSequence().flatMap { it.songs.asSequence() }
            .filter { LocalSongSupport.isLocalSong(it, context) }
            .filter { RecordingMatchPolicy.canonicalTitle(it.originalName ?: it.name) == title }
            .mapNotNull(::sourceCandidateForSong).distinctBy { it.key }
            .sortedByDescending { assessSourceChoice(song, it).accepted }.take(12).toList()
        SourceChoiceDiscoveryResult(candidates)
    }

private suspend fun discoverBiliChoices(song: SongItem): SourceChoiceDiscoveryResult = supervisorScope {
    val expected = song.recordingMetadata()
    val keyword = listOf(expected.title, expected.artist).filter { it.isNotBlank() }.joinToString(" ")
    if (keyword.isBlank()) return@supervisorScope SourceChoiceDiscoveryResult(emptyList())
    val videos = AppContainer.biliClient.searchVideos(keyword).items.filter { it.aid > 0 }
        .distinctBy { it.aid }.take(5)
    val semaphore = Semaphore(3)
    val pages = videos.map { searchItem ->
        async {
            semaphore.withPermit {
                try {
                    val info = AppContainer.biliClient.getVideoBasicInfoByAvid(searchItem.aid)
                    // Bind every page to the verified video; never guess the first CID.
                    if (info.aid != searchItem.aid || info.aid <= 0) {
                        return@withPermit SourceChoiceDiscoveryResult(emptyList(), incomplete = true)
                    }
                    val candidates = info.pages.asSequence().filter { it.cid > 0 && it.page > 0 }
                        .distinctBy { it.cid }.map { page ->
                            val metadata = biliPageRecordingMetadata(expected, info, page, searchItem.titlePlain)
                            RecordingSourceCandidate(
                                SongSourceKey("bilibili", info.aid.toString(), page.cid.toString()),
                                UnifiedSongMetadata(metadata.title, metadata.artist, metadata.versionContext, metadata.durationMs,
                                    RecordingMatchPolicy.versions(metadata).singleOrNull() ?: RecordingVersion.UNKNOWN)
                            )
                        }.sortedByDescending { assessSourceChoice(song, it).accepted }.take(24).toList()
                    SourceChoiceDiscoveryResult(candidates)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SourceChoiceDiscoveryResult(emptyList(), incomplete = true)
                }
            }
        }
    }.awaitAll()
    SourceChoiceDiscoveryResult(pages.flatMap { it.candidates }, pages.any { it.incomplete })
}
