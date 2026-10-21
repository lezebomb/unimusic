package moe.ouom.neriplayer.platform.netease.importing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.unified.ImportFailureKind
import moe.ouom.neriplayer.data.model.unified.PlaylistImportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePlaylistImportProviderTest {
    @Test fun pagesAndOrderedSnapshotKeepMissingDetailsExplicit() = runTest {
        val transport = FixtureTransport()
        val provider = NeteasePlaylistImportProvider(transport)
        val first = provider.listPlaylists("7", null, 1)
        assertEquals("1", first.nextCursor)
        assertEquals(listOf("10"), first.playlists.map { it.remoteId })
        assertEquals("收藏的歌单", provider.listPlaylists("7", first.nextCursor, 1).playlists.single().name)
        val snapshot = provider.readSnapshot("7", "10")
        assertEquals(listOf("3", "1", "2"), snapshot.resourceIds)
        assertEquals(snapshot.snapshotId, provider.readSnapshot("7", "10").snapshotId)
        val batch = provider.readSongs("7", snapshot.resourceIds)
        assertEquals(listOf("3", "1"), batch.songs.map { it.source.resourceId })
        assertEquals("歌手甲 / 歌手乙", batch.songs.first().metadata.artist)
        assertEquals("专辑", batch.songs.first().metadata.album)
        assertEquals("cover", batch.songs.first().coverUrl)
        assertEquals(listOf("2"), batch.failures.map { it.resourceId })
        assertEquals(ImportFailureKind.NOT_FOUND, batch.failures.single().kind)
        assertEquals(listOf(listOf(3L, 1L, 2L)), transport.requestedSongs)
    }

    @Test fun incompleteSnapshotsAndStalledCatalogPagesFail() = runTest {
        val transport = FixtureTransport()
        val provider = NeteasePlaylistImportProvider(transport)
        transport.detail = """{"code":200,"playlist":{"id":10,"name":"保留本地","trackCount":3,"trackIds":[]}}"""
        assertFailure(ImportFailureKind.INCOMPLETE_RESPONSE) { provider.readSnapshot("7", "10") }
        transport.catalog = """{"code":200,"playlist":[],"more":true}"""
        assertFailure(ImportFailureKind.INCOMPLETE_RESPONSE) { provider.listPlaylists("7") }
        transport.detail = """{"code":200,"playlist":{"id":10,"name":"空歌单","trackCount":0,"trackIds":[]}}"""
        assertTrue(provider.readSnapshot("7", "10").resourceIds.isEmpty())
    }

    @Test fun wrongExpiredOrChangedAccountCannotReturnResults() = runTest {
        val transport = FixtureTransport()
        val provider = NeteasePlaylistImportProvider(transport)
        assertFailure(ImportFailureKind.ACCOUNT_CHANGED) { provider.readSnapshot("8", "10") }
        transport.account = """{"code":301}"""
        assertFailure(ImportFailureKind.AUTH_REQUIRED) { provider.readSnapshot("7", "10") }
        transport.account = """{"code":200,"account":{"id":7}}"""
        transport.changeSessionOnSongs = true
        assertFailure(ImportFailureKind.ACCOUNT_CHANGED) { provider.readSongs("7", listOf("3")) }
        transport.cancel = true
        var cancelled = false
        try { provider.listPlaylists("7") } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    private suspend fun assertFailure(kind: ImportFailureKind, block: suspend () -> Any) {
        var failure: PlaylistImportException? = null
        try { block() } catch (error: PlaylistImportException) { failure = error }
        assertEquals(kind, failure?.kind)
    }
}

private class FixtureTransport : NeteaseImportTransport {
    var identity = Any()
    var account = """{"code":200,"profile":{"userId":7}}"""
    var catalog: String? = null
    var detail = """{"code":200,"playlist":{"id":10,"name":"原顺序","coverImgUrl":"playlist-cover","trackCount":3,"updateTime":42,"trackIds":[{"id":3},{"id":1},{"id":2}]}}"""
    var changeSessionOnSongs = false
    var cancel = false
    val requestedSongs = mutableListOf<List<Long>>()
    override fun sessionIdentity() = identity
    override suspend fun currentAccount(): String {
        if (cancel) throw CancellationException("fixture cancellation")
        return account
    }
    override suspend fun playlists(userId: Long, offset: Int, limit: Int): String = catalog ?: if (offset == 0)
        """{"code":200,"more":true,"playlist":[{"id":10,"name":"创建的歌单","trackCount":3}]}""" else
        """{"code":200,"more":false,"playlist":[{"id":11,"name":"收藏的歌单","trackCount":0}]}"""
    override suspend fun playlist(id: Long) = detail
    override suspend fun songs(ids: List<Long>): String {
        requestedSongs += ids.toList()
        if (changeSessionOnSongs) identity = Any()
        return """{"code":200,"songs":[{"id":1,"name":"第一首","dt":100},{"id":3,"name":"第三首","ar":[{"name":"歌手甲"},{"name":"歌手乙"}],"al":{"name":"专辑","picUrl":"cover"},"dt":180000}]}"""
    }
}
