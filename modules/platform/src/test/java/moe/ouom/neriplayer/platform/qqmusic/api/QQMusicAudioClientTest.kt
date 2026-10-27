package moe.ouom.neriplayer.platform.qqmusic.api

import moe.ouom.neriplayer.data.model.unified.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

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
}
