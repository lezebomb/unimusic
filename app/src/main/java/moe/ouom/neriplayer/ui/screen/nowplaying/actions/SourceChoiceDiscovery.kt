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
import moe.ouom.neriplayer.core.player.resolver.netease.biliRecordingMetadata
import moe.ouom.neriplayer.core.player.resolver.netease.recordingMetadata
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.unified.RecordingMatchPolicy
import moe.ouom.neriplayer.data.model.unified.RecordingMetadata
import moe.ouom.neriplayer.data.model.unified.RecordingSourceCandidate
import moe.ouom.neriplayer.data.model.unified.RecordingVersion
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import moe.ouom.neriplayer.data.traffic.isOfflineModeNow
import moe.ouom.neriplayer.data.unified.sourceCandidateForSong

internal data class SourceChoiceDiscoveryResult(
    val candidates: List<RecordingSourceCandidate>,
    val incomplete: Boolean = false,
    val offline: Boolean = false
)

internal fun assessSourceChoice(song: SongItem, candidate: RecordingSourceCandidate) =
    RecordingMatchPolicy.assess(song.recordingMetadata(), candidate.recordingMetadata(),
        if (candidate.key.provider == "local") 8_000 else 4_000)

private fun RecordingSourceCandidate.recordingMetadata() = metadata.let {
    RecordingMetadata(it.title, it.artist, it.durationMs, it.version, it.album)
}

/** Metadata discovery only: no audio URLs, unlock services, or entitlement changes. */
internal suspend fun discoverSourceChoices(context: Context, song: SongItem): SourceChoiceDiscoveryResult =
    supervisorScope {
        val offline = context.isOfflineModeNow()
        val local = async { boundedCandidates { discoverLocalChoices(context, song) } }
        val bili = async {
            if (offline) SourceChoiceDiscoveryResult(emptyList())
            else boundedCandidates { discoverBiliChoices(song) }
        }
        val results = listOf(local, bili).awaitAll()
        val originalKey = sourceCandidateForSong(song)?.key
        SourceChoiceDiscoveryResult(
            candidates = results.flatMap { it.candidates }.filter { it.key != originalKey }.distinctBy { it.key }
                .sortedByDescending { assessSourceChoice(song, it).accepted }.take(24),
            incomplete = results.any { it.incomplete },
            offline = offline
        )
    }

private suspend fun boundedCandidates(
    discover: suspend () -> SourceChoiceDiscoveryResult
): SourceChoiceDiscoveryResult = try {
    withTimeoutOrNull(8_000) { discover() }
        ?: SourceChoiceDiscoveryResult(emptyList(), incomplete = true)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    SourceChoiceDiscoveryResult(emptyList(), incomplete = true)
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
                            val generic = page.part.trim()
                                .matches(Regex("(?i)(?:p?1|正片|视频|音频|默认|video|audio)"))
                            val title = if (info.pages.size == 1 && generic) info.title else page.part
                            val evidence = "${searchItem.titlePlain} · ${info.title} · P${page.page} ${page.part} · ${info.ownerName}"
                            val duration = page.durationSec.takeIf { it > 0 }?.times(1000L)
                                ?: if (info.pages.size == 1) info.durationSec.coerceAtLeast(0) * 1000L else 0L
                            val metadata = biliRecordingMetadata(expected, title, evidence, info.ownerName, duration)
                            RecordingSourceCandidate(
                                SongSourceKey("bilibili", info.aid.toString(), page.cid.toString()),
                                UnifiedSongMetadata(metadata.title, metadata.artist, evidence, duration,
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
