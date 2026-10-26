package moe.ouom.neriplayer.core.player.resolver.netease

import moe.ouom.neriplayer.core.player.policy.source.SourceFailure
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoPage
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import moe.ouom.neriplayer.data.model.bilibili.playback.PlayInfo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BiliRecordingMatchTest {
    private val song = SongItem(1, "晴天", "周杰伦", "叶惠美", 1, 269_000, null)
    private fun page(cid: Long, title: String) = VideoPage(cid, cid.toInt(), title, 269, 0, 0)
    private fun video(vararg pages: VideoPage) = VideoBasicInfo(1, "BVfixture", "周杰伦 - 晴天【Hi-Res】", "", "", 269,
        1, "上传者", "", VideoStats(0, 0, 0, 0, 0, 0, 0), pages.toList())

    @Test fun oneMatchingPageUsesItsOwnCidAndFullMetadata() {
        val info = video(page(11, "别的歌"), page(22, "晴天"))
        assertEquals(22L, selectStrictBiliPage(song, info, info.title).page?.cid)
        assertNull(selectStrictBiliPage(song.copy(artist = "另一个歌手"), info, info.title).page)
        assertNull(selectStrictBiliPage(song.copy(durationMs = 300_000), info, info.title).page)
    }

    @Test fun headerCannotRescueWrongVersionOrAmbiguousPages() {
        val wrong = video(page(11, "晴天 (Live)"), page(22, "别的歌"))
        assertNull(selectStrictBiliPage(song, wrong, wrong.title).page)
        val ambiguous = video(page(11, "晴天"), page(22, "晴天"))
        assertEquals(SourceFailure.AMBIGUOUS, selectStrictBiliPage(song, ambiguous, ambiguous.title).rejection)
        val cover = video(page(11, "晴天")).copy(title = "周杰伦 - 晴天 翻唱")
        assertNull(selectStrictBiliPage(song, cover, cover.title).page)
    }

    @Test fun genericSinglePartCanUseVideoTitleButArtistSubstringCannotMatch() {
        val info = video(page(11, "正片"))
        assertEquals(11L, selectStrictBiliPage(song, info, info.title).page?.cid)
        val asia = info.copy(title = "Asia", pages = listOf(page(11, "Asia")))
        assertNull(selectStrictBiliPage(song.copy(name = "A", artist = "Sia"), asia, asia.title).page)
    }

    @Test fun confirmedCidDisambiguatesOnlyTheSelectedCompatiblePage() {
        val info = video(page(11, "晴天"), page(22, "晴天"), page(33, "晴天 Live"))
        assertEquals(22L, selectStrictBiliPage(song, info, info.title, selectedCid = 22).page?.cid)
        assertNull(selectStrictBiliPage(song, info, info.title, selectedCid = 33).page)
        assertNull(selectStrictBiliPage(song, info, info.title, selectedCid = 99).page)
    }

    @Test fun previewDrmAndIncompleteStreamsAreNotFullSongSources() {
        fun info(raw: String, duration: Long? = 269_000) = PlayInfo(0, "", null, null, duration,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, null, JSONObject(raw))
        assertNull(biliAutoSourceAccessFailure(info("{}"), 269_000))
        assertEquals(SourceFailure.PREVIEW_ONLY, biliAutoSourceAccessFailure(info("{\"data\":{\"is_preview\":true}}"), 269_000))
        assertEquals(SourceFailure.PREVIEW_ONLY, biliAutoSourceAccessFailure(info("{}", 60_000), 269_000))
        assertEquals(SourceFailure.EXTERNAL_PLAYER_REQUIRED, biliAutoSourceAccessFailure(info("{\"data\":{\"is_drm\":1}}"), 269_000))
        assertEquals(SourceFailure.UNAVAILABLE, biliAutoSourceAccessFailure(info("{}", null), 269_000))
    }

    @Test fun onlyActualAutoSourceFailuresTriggerCooldown() {
        assertTrue(shouldCoolDownAutoSource("bili-auto-bv-1", "playback_error_ERROR_CODE_IO"))
        assertTrue(shouldCoolDownAutoSource("bili-auto-bv-1", "startup_stall_BUFFERING"))
        assertFalse(shouldCoolDownAutoSource("bili-auto-bv-1", "stale_resume"))
        assertFalse(shouldCoolDownAutoSource("bili-auto-bv-1", "startup_stall_offline_cache"))
        assertFalse(shouldCoolDownAutoSource("netease-1", "playback_error_ERROR_CODE_IO"))
    }
}
