package moe.ouom.neriplayer.sources

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.discoverSourceChoices
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device check for the source sheet entry of the matching bridge.
 *
 * The sheet may only offer the bridge when it is switched on, has an address, and describes the
 * very recording being played; enabling it must never widen the offered identities.
 */
@RunWith(AndroidJUnit4::class)
class UnmBridgeSourceChoiceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext.applicationContext
    private val bridgeUrl get() = InstrumentationRegistry.getArguments()
        .getString("unmBridgeUrl") ?: "http://127.0.0.1:8900"

    private val song = SongItem(
        id = 2142857204L,
        name = "雑踏、僕らの街",
        artist = "トゲナシトゲアリ",
        album = "",
        albumId = 0L,
        durationMs = 184243L,
        coverUrl = null,
        channelId = "netease",
        audioId = "2142857204"
    )

    private lateinit var settings: SettingsRepository

    @Before
    fun prepare() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("unimusicLiveUnmBridge") == "true")
        settings = SettingsRepository(context)
        settings.setUnmBridgeBaseUrl(bridgeUrl)
        settings.setUnmBridgeEnabled(true)
    }

    @After
    fun reset() = runBlocking {
        if (::settings.isInitialized) {
            settings.setUnmBridgeEnabled(false)
            settings.setUnmBridgeBaseUrl("")
        }
    }

    @Test
    fun sheetOffersTheBridgeOnlyForThePlayedRecording() = runBlocking {
        val enabled = discoverSourceChoices(context, song, youtubeEnabled = false)
        val bridgeChoices = enabled.candidates.filter { it.key.provider == "unm_netease" }
        assertFalse("source sheet did not offer the bridge", bridgeChoices.isEmpty())
        assertTrue(
            "bridge choice must reuse the played NetEase id",
            bridgeChoices.all { it.key.resourceId == song.id.toString() && it.key.subResourceId.isEmpty() }
        )

        settings.setUnmBridgeEnabled(false)
        val disabled = discoverSourceChoices(context, song, youtubeEnabled = false)
        assertTrue(
            "disabled bridge must not appear in the sheet",
            disabled.candidates.none { it.key.provider == "unm_netease" }
        )
    }
}
