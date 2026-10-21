package moe.ouom.neriplayer.platform.netease.importing

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.unified.ImportFailureKind
import moe.ouom.neriplayer.data.model.unified.ImportPlaylistPage
import moe.ouom.neriplayer.data.model.unified.ImportPlaylistSnapshot
import moe.ouom.neriplayer.data.model.unified.ImportPlaylistSummary
import moe.ouom.neriplayer.data.model.unified.ImportSong
import moe.ouom.neriplayer.data.model.unified.ImportSongBatch
import moe.ouom.neriplayer.data.model.unified.ImportSongFailure
import moe.ouom.neriplayer.data.model.unified.PlaylistImportException
import moe.ouom.neriplayer.data.model.unified.PlaylistImportProvider
import moe.ouom.neriplayer.data.model.unified.SongSourceKey
import moe.ouom.neriplayer.data.model.unified.UnifiedSongMetadata
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.json.JSONArray
import org.json.JSONObject

/** Narrow transport also allows fixture tests without cookies, accounts or network access. */
internal interface NeteaseImportTransport {
    fun sessionIdentity(): Any
    suspend fun currentAccount(): String
    suspend fun playlists(userId: Long, offset: Int, limit: Int): String
    suspend fun playlist(id: Long): String
    suspend fun songs(ids: List<Long>): String
}

class NeteasePlaylistImportProvider internal constructor(
    private val transport: NeteaseImportTransport
) : PlaylistImportProvider {
    constructor(client: NeteaseClient) : this(object : NeteaseImportTransport {
        override fun sessionIdentity(): Any = client.importSessionIdentity()
        override suspend fun currentAccount() = client.getCurrentUserAccountCancellable()
        override suspend fun playlists(userId: Long, offset: Int, limit: Int) =
            client.getUserPlaylistsCancellable(userId, offset, limit)
        override suspend fun playlist(id: Long) = client.getPlaylistDetailCancellable(id, n = 0)
        override suspend fun songs(ids: List<Long>) = client.getSongDetailCancellable(ids)
    })

    override val providerId = "netease"

    override suspend fun listPlaylists(accountKey: String, cursor: String?, limit: Int): ImportPlaylistPage {
        require(limit in 1..100)
        val offset = cursor?.toIntOrNull()?.takeIf { it >= 0 }
            ?: if (cursor == null) 0 else fail(ImportFailureKind.INVALID_RESPONSE)
        return withAccount(accountKey) { userId ->
            val root = response(transport.playlists(userId, offset, limit))
            val rows = root.optJSONArray("playlist") ?: fail(ImportFailureKind.INCOMPLETE_RESPONSE)
            val playlists = (0 until rows.length()).map { summary(rows.getJSONObject(it)) }
            val more = root.optBoolean("more", false)
            if (more && playlists.isEmpty()) fail(ImportFailureKind.INCOMPLETE_RESPONSE)
            ImportPlaylistPage(playlists, if (more) Math.addExact(offset, rows.length()).toString() else null)
        }
    }

    override suspend fun readSnapshot(accountKey: String, remotePlaylistId: String): ImportPlaylistSnapshot =
        withAccount(accountKey) {
            val root = response(transport.playlist(positiveId(remotePlaylistId)))
            val playlist = root.optJSONObject("playlist") ?: fail(ImportFailureKind.INCOMPLETE_RESPONSE)
            val info = summary(playlist)
            if (info.remoteId != remotePlaylistId) fail(ImportFailureKind.INVALID_RESPONSE)
            val ids = playlist.optJSONArray("trackIds") ?: fail(ImportFailureKind.INCOMPLETE_RESPONSE)
            val ordered = (0 until ids.length()).map { positiveId(ids.getJSONObject(it).optString("id")).toString() }
            // An empty/partial server response must never masquerade as a complete remote library.
            if (ordered.size != info.trackCount) fail(ImportFailureKind.INCOMPLETE_RESPONSE)
            val revision = playlist.optLong("updateTime", 0).toString()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest((revision + ":" + ordered.joinToString(",")).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            ImportPlaylistSnapshot(providerId, accountKey, info, digest, ordered)
        }

    override suspend fun readSongs(accountKey: String, resourceIds: List<String>): ImportSongBatch {
        require(resourceIds.size in 1..200)
        val requested = resourceIds.map { positiveId(it) }.distinct()
        return withAccount(accountKey) {
            val rows = response(transport.songs(requested)).optJSONArray("songs")
                ?: fail(ImportFailureKind.INCOMPLETE_RESPONSE)
            val found = mutableMapOf<Long, ImportSong>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optLong("id", 0)
                if (id !in requested || id in found || row.optString("name").isBlank()) continue
                val album = row.optJSONObject("al") ?: row.optJSONObject("album")
                val artists = row.optJSONArray("ar") ?: row.optJSONArray("artists") ?: JSONArray()
                val artist = (0 until artists.length()).mapNotNull {
                    artists.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
                }.joinToString(" / ")
                found[id] = ImportSong(SongSourceKey(providerId, id.toString()),
                    UnifiedSongMetadata(row.getString("name"), artist, album?.optString("name").orEmpty(),
                        row.optLong("dt", row.optLong("duration", 0)).coerceAtLeast(0)),
                    album?.optString("picUrl")?.takeIf(String::isNotBlank))
            }
            ImportSongBatch(requested.mapNotNull(found::get), requested.filterNot(found::containsKey).map {
                ImportSongFailure(it.toString(), ImportFailureKind.NOT_FOUND)
            })
        }
    }

    private suspend fun <T> withAccount(accountKey: String, block: suspend (Long) -> T): T {
        val userId = positiveId(accountKey)
        val session = transport.sessionIdentity()
        try {
            val account = response(transport.currentAccount())
            val actual = account.optJSONObject("profile")?.optLong("userId", 0)
                ?.takeIf { it > 0 } ?: account.optJSONObject("account")?.optLong("id", 0) ?: 0
            if (actual <= 0) fail(ImportFailureKind.AUTH_REQUIRED)
            if (actual != userId || session !== transport.sessionIdentity()) fail(ImportFailureKind.ACCOUNT_CHANGED)
            val result = block(userId)
            if (session !== transport.sessionIdentity()) fail(ImportFailureKind.ACCOUNT_CHANGED)
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PlaylistImportException) {
            throw failure
        } catch (_: java.io.IOException) {
            fail(ImportFailureKind.TEMPORARY)
        } catch (_: org.json.JSONException) {
            fail(ImportFailureKind.INVALID_RESPONSE)
        } catch (_: ArithmeticException) {
            fail(ImportFailureKind.INVALID_RESPONSE)
        }
    }

    private fun summary(row: JSONObject): ImportPlaylistSummary {
        val id = positiveId(row.optString("id")).toString()
        val name = row.optString("name").takeIf(String::isNotBlank) ?: fail(ImportFailureKind.INVALID_RESPONSE)
        val count = row.optInt("trackCount", -1).takeIf { it >= 0 } ?: fail(ImportFailureKind.INCOMPLETE_RESPONSE)
        return ImportPlaylistSummary(id, name, row.optString("coverImgUrl").takeIf(String::isNotBlank), count)
    }

    private fun response(raw: String): JSONObject = JSONObject(raw).also {
        when (it.optInt("code", -1)) {
            200 -> Unit
            301, 401 -> fail(ImportFailureKind.AUTH_REQUIRED)
            403 -> fail(ImportFailureKind.ACCESS_DENIED)
            404 -> fail(ImportFailureKind.NOT_FOUND)
            429, 500, 502, 503, 504 -> fail(ImportFailureKind.TEMPORARY)
            else -> fail(ImportFailureKind.INVALID_RESPONSE)
        }
    }
}

private fun positiveId(raw: String): Long = raw.toLongOrNull()?.takeIf { it > 0 && it.toString() == raw }
    ?: fail(ImportFailureKind.INVALID_RESPONSE)

private fun fail(kind: ImportFailureKind): Nothing = throw PlaylistImportException(kind)
