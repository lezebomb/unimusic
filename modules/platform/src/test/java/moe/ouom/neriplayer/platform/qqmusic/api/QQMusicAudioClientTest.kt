package moe.ouom.neriplayer.platform.qqmusic.api

import moe.ouom.neriplayer.data.model.unified.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import moe.ouom.neriplayer.common.search.FederatedSearchEngine
import moe.ouom.neriplayer.common.search.FederatedSearchProvider
import moe.ouom.neriplayer.common.search.SearchPage
import moe.ouom.neriplayer.common.search.SearchSourceFailure
import moe.ouom.neriplayer.platform.search.api.client.KuwoCatalogProvider
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody

class QQMusicAudioClientTest {
    private val mid = "003OUlho2HcRHC"
    private val file = "M50000000000000000.mp3"
    private val metadata = UnifiedSongMetadata("Song", "Artist", "Album", 200_000)

    @Test fun normalFullMp3RequiresExactIdentityAndSafeCdnPath() {
        fun response(path: String, name: String = file, key: String = "", result: Int = 0) = JSONObject()
            .put("code", 0).put("data", JSONObject().put("midurlinfo", org.json.JSONArray().put(JSONObject()
                .put("songmid", mid).put("filename", name).put("result", result).put("purl", path).put("ekey", key))))
        assertEquals(SourceCheckStatus.ADDRESS_RESOLVED,
            parseQQAudioResponse(response("$file?vkey=fixture"), mid, file, metadata, true).status)
        for (bad in listOf("https://evil.test/$file", "../$file", "RS0200000000000000.mp3?vkey=x")) {
            assertNull(parseQQAudioResponse(response(bad), mid, file, metadata, true).url)
        }
        assertNull(parseQQAudioResponse(response("$file?vkey=x", name = "RS02preview.mp3"), mid, file, metadata, true).url)
        assertEquals(SourceCheckStatus.EXTERNAL_PLAYER_REQUIRED,
            parseQQAudioResponse(response("$file?vkey=x", key = "encrypted"), mid, file, metadata, true).status)
        assertEquals(SourceCheckStatus.NO_PERMISSION,
            parseQQAudioResponse(response("", result = 104003), mid, file, metadata, true).status)
        assertEquals(SourceCheckStatus.AUTH_REQUIRED,
            parseQQAudioResponse(response("", result = 104003), mid, file, metadata, false).status)
    }

    @Test fun detailKeepsVersionEvidenceAndRejectsDifferentMid() {
        val row = JSONObject("""{"mid":"$mid","title":"Song","subtitle":"Live","interval":200,"singer":[{"name":"Artist"}],"album":{"name":"Album"}}""")
        val fresh = parseQQAudioMetadata(row, mid)!!
        assertEquals(setOf(RecordingVersion.LIVE), RecordingMatchPolicy.versions(RecordingMetadata(fresh.title, fresh.artist, fresh.durationMs)))
        assertNull(parseQQAudioMetadata(row, "00000000000000"))
        row.put("subtitle", "电视剧主题曲")
        assertEquals("Song", parseQQAudioMetadata(row, mid)!!.title)
    }

    @Test fun searchServiceFailuresStaySeparateFromTransportAndKeepHealthyResults() = runBlocking {
        for (providerId in listOf("qqmusic", "kuwo")) {
            for (kind in listOf("http", "invalid", "empty", "network", "timeout")) {
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    if (kind == "network") throw java.io.IOException("fixture transport failure")
                    if (kind == "timeout") throw java.net.SocketTimeoutException("fixture timeout")
                    val body = if (kind == "invalid") "{}" else if (providerId == "qqmusic")
                        """{"code":0,"data":{"song":{"list":[],"totalnum":0}}}""" else """{"abslist":[],"PN":"0","TOTAL":"0"}"""
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(if (kind == "http") 500 else 200).message("fixture")
                        .body(body.toResponseBody()).build()
                }.build()
                val provider: MusicCatalogProvider = if (providerId == "qqmusic") QQMusicAudioClient(client) else KuwoCatalogProvider(client)
                val engine = FederatedSearchEngine(listOf(
                    FederatedSearchProvider<String>(providerId) { query, cursor ->
                        SearchPage(provider.search(query, cursor, 20).tracks.map { it.source.toString() })
                    },
                    FederatedSearchProvider("healthy") { _: String, _: String? -> SearchPage(listOf("ready")) }
                ), identity = { it })
                val result = engine.search("fixture").toList().last()
                assertEquals(listOf("ready"), result.interleavedItems())
                val expected = when (kind) {
                    "network" -> SearchSourceFailure.NETWORK
                    "timeout" -> SearchSourceFailure.TIMEOUT
                    "empty" -> null
                    else -> SearchSourceFailure.UNAVAILABLE
                }
                assertEquals("$providerId / $kind", expected, result.sources.getValue(providerId).failure)
                assertEquals(kind != "empty", providerId in result.retryableSourceIds)
            }
        }
    }

}
