package moe.ouom.neriplayer.core.player.engine.datasource

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import moe.ouom.neriplayer.data.model.playback.streamOnlyPlaybackUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StreamPolicyDataSourceFactoryTest {
    @Test fun streamOnlyMediaNeverOpensCacheEvenWithoutACustomKey() {
        var cached = 0
        var upstream = 0
        val factory = StreamPolicyDataSourceFactory(
            DataSource.Factory { cached++; ByteArrayDataSource(byteArrayOf(1)) },
            DataSource.Factory { upstream++; ByteArrayDataSource(byteArrayOf(2)) })
        val source = factory.createDataSource()
        source.open(DataSpec.Builder().setUri(streamOnlyPlaybackUrl("https://m801.music.126.net/a.mp3")).build())
        val buffer = ByteArray(1)
        source.read(buffer, 0, 1)
        assertEquals(2, buffer[0].toInt()); source.close()
        assertEquals(0, cached); assertEquals(1, upstream)
        source.open(DataSpec.Builder().setUri("https://m801.music.126.net/b.mp3").build())
        source.read(buffer, 0, 1)
        assertEquals(1, buffer[0].toInt()); source.close()
        assertEquals(1, cached); assertEquals(1, upstream)
    }
}
