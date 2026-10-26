package moe.ouom.neriplayer.platform.search.api.client

import java.io.IOException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.unified.SourcePlaybackMode
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class KuwoCatalogProviderTest {
    @Test fun ordinarySearchIsHttpsCancellableAndUsesOnlyCatalogParameters() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https", request.url.scheme)
            assertEquals("search.kuwo.cn", request.url.host)
            assertEquals("晴天 周杰伦", request.url.queryParameter("all"))
            assertEquals(setOf("all", "ft", "pn", "rn", "rformat", "encoding"), request.url.queryParameterNames)
            assertNull(request.header("Cookie"))
            assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{'PN':'0','TOTAL':'0','abslist':[]}".toResponseBody()).build()
        }.build()
        val provider = KuwoCatalogProvider(client)
        val page = provider.search("  晴天　周杰伦  ", limit = 5)
        assertEquals(SourcePlaybackMode.METADATA_ONLY, provider.playbackMode)
        assertTrue(page.tracks.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test fun singleQuotedMetadataIsDecodedWithoutTurningPaymentFlagsIntoAudioAccess() {
        val page = parseKuwoCatalogSearch("""{'PN':'0','TOTAL':'9','abslist':[
            {'MUSICRID':'MUSIC_1','SONGNAME':'晴天&nbsp;Live','ARTIST':'A&amp;B','ALBUM':'Album','DURATION':'269','PAY':'0','COPYRIGHT':'0'},
            {'MUSICRID':'MUSIC_1','SONGNAME':'duplicate','ARTIST':'A','DURATION':'269'},
            {'MUSICRID':'MUSIC_2','SONGNAME':'Other','ARTIST':'C\\\\u0026D','DURATION':'-1','PAY':'1'},
            {'MUSICRID':'MUSIC_3','SONGNAME':'bad','ARTIST':''}
        ]}""", 0, 4)
        assertEquals(listOf("1", "2"), page.tracks.map { it.source.resourceId })
        assertEquals("晴天 Live", page.tracks.first().metadata.title)
        assertEquals("A&B", page.tracks.first().metadata.artist)
        assertEquals(269_000L, page.tracks.first().metadata.durationMs)
        assertEquals(0L, page.tracks.last().metadata.durationMs)
        assertEquals("C&D", page.tracks.last().metadata.artist)
        assertTrue(page.tracks.all { it.playbackMode == SourcePlaybackMode.METADATA_ONLY })
        assertEquals("1", page.nextCursor)
    }

    @Test fun malformedPagesAndInvalidCursorsFailInsteadOfAppearingAsNoResults() {
        assertThrows(IOException::class.java) { parseKuwoCatalogSearch("{}", 0, 20) }
        assertThrows(IOException::class.java) { parseKuwoCatalogSearch("{'PN':'3','abslist':[]}", 0, 20) }
        listOf("-1", "1.2", "01", "", "2147483648").forEach { cursor ->
            assertThrows(IllegalArgumentException::class.java) { parseKuwoPageCursor(cursor) }
        }
        assertThrows(IllegalArgumentException::class.java) { buildKuwoCatalogSearchUrl("song", 0, 51) }
    }
}
