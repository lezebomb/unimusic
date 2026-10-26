package moe.ouom.neriplayer.core.player.resolver.netease

import java.util.LinkedHashMap
import moe.ouom.neriplayer.core.player.policy.source.SourceFailureCooldown
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.search.SearchVideoItem

internal object NeteaseAutoSourceState {
    val cooldown = SourceFailureCooldown()
    private data class Winner(val candidate: SearchVideoItem, val expires: Long)
    private val winners = LinkedHashMap<String, Winner>()
    fun key(song: SongItem): String = listOf(song.id, song.originalName ?: song.name,
        song.originalArtist ?: song.artist, song.album, song.durationMs).joinToString("\u0000")
    fun candidateId(candidate: SearchVideoItem): String = candidate.bvid.ifBlank { "av${candidate.aid}" }
    private fun now() = System.nanoTime() / 1_000_000

    @Synchronized fun previous(song: SongItem): SearchVideoItem? {
        winners.entries.removeAll { it.value.expires <= now() }
        return winners[key(song)]?.candidate
    }

    @Synchronized fun selected(song: SongItem, candidate: SearchVideoItem) {
        winners[key(song)] = Winner(candidate, now() + 10 * 60_000)
        while (winners.size > 128) winners.remove(winners.keys.first())
    }

    fun failed(song: SongItem, activeCacheKey: String?, reason: String) {
        if (!shouldCoolDownAutoSource(activeCacheKey, reason)) return
        previous(song)?.let { cooldown.reject(key(song), candidateId(it)) }
    }
}

internal fun shouldCoolDownAutoSource(cacheKey: String?, reason: String): Boolean =
    cacheKey?.startsWith("bili-auto-") == true && !reason.endsWith("offline_cache") &&
        (reason.startsWith("playback_error_") || reason.startsWith("startup_stall_") || reason.startsWith("runtime_stall_"))
