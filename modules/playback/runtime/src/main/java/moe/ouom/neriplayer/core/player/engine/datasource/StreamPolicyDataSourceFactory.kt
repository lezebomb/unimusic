@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine.datasource

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import moe.ouom.neriplayer.data.model.playback.isStreamOnlyPlaybackUrl

/** Route stream-only media around CacheDataSource itself, including reads and writes. */
internal class StreamPolicyDataSourceFactory(
    private val cached: DataSource.Factory, private val upstream: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            active?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            check(active == null)
            val factory = if (isStreamOnlyPlaybackUrl(dataSpec.uri.toString())) upstream else cached
            val source = factory.createDataSource()
            active = source
            listeners.forEach(source::addTransferListener)
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(active).read(buffer, offset, length)
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
        override fun close() {
            val source = active
            active = null
            source?.close()
        }
    }
}
