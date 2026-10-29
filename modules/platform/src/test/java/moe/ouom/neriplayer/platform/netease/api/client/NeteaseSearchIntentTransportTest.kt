package moe.ouom.neriplayer.platform.netease.api.client

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Manual anonymous public metadata check; never reads stored app accounts or emits URLs/headers. */
class NeteaseSearchIntentTransportTest {
    @Test fun optInPublicSearchIntentProbe() = runBlocking {
        assumeTrue(System.getenv("UNIMUSIC_LIVE_SEARCH_INTENT_PROBE") == "1")
        try {
            withTimeout(12_000) {
                val client = NeteaseClient { "" }
                for (type in listOf(1, 100)) {
                    val root = JSONObject(client.searchSongsCancellable("哭泣少女乐队", 12, 0, type, false))
                    val result = root.optJSONObject("result")
                    val rows = result?.optJSONArray(if (type == 1) "songs" else "artists")
                    println("INTENT_PROBE type=$type code=${root.optInt("code")} count=${rows?.length() ?: 0}")
                    assumeTrue("Anonymous search did not return usable metadata", root.optInt("code") == 200)
                    if (rows != null) for (i in 0 until minOf(rows.length(), 6)) {
                        val row = rows.getJSONObject(i)
                        val artists = row.optJSONArray("ar")
                        val credits = if (artists == null) "" else (0 until artists.length()).joinToString("/") {
                            artists.getJSONObject(it).optString("name") }
                        println("INTENT_PROBE rank=${i+1} id=${row.optLong("id")} name=${row.optString("name")} artist=$credits")
                    }
                }
            }
        } catch (rejected: org.junit.AssumptionViolatedException) {
            throw rejected
        } catch (error: Exception) {
            throw AssertionError("Public metadata probe failed: ${error.javaClass.simpleName}")
        }
    }
}
