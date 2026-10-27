package moe.ouom.neriplayer.data.sync.unified

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GuardedIdentityRemoteTransportTest {
    @Test fun `configuration changed during read discards the old response`() = runTest {
        var current = true
        var writes = 0
        val delegate = object : UnifiedIdentityRemoteTransport<Int> {
            override suspend fun read(): RemoteIdentitySnapshot<Int> { current = false;return RemoteIdentitySnapshot(byteArrayOf(1), 1) }
            override suspend fun compareAndSet(expected: Int, bytes: ByteArray): Boolean { writes++;return true }
        }
        val guarded = GuardedIdentityRemoteTransport(delegate) { current }
        try { guarded.read();fail("Old configuration response accepted") } catch (_: IdentitySyncConfigurationChangedException) { }
        try { guarded.compareAndSet(1, byteArrayOf(2));fail("Old configuration write attempted") }
        catch (_: IdentitySyncConfigurationChangedException) { }
        assertEquals(0, writes)
    }

    @Test fun `configuration changed during upload never acknowledges success for the new target`() = runTest {
        var current = true
        var writes = 0
        val delegate = object : UnifiedIdentityRemoteTransport<Int> {
            override suspend fun read() = RemoteIdentitySnapshot<Int>(null, 0)
            override suspend fun compareAndSet(expected: Int, bytes: ByteArray): Boolean { writes++;current = false;return true }
        }
        val guarded = GuardedIdentityRemoteTransport(delegate) { current }
        try { guarded.compareAndSet(0, byteArrayOf(1));fail("Old upload acknowledged") }
        catch (_: IdentitySyncConfigurationChangedException) { }
        assertEquals(1, writes) // An already-sent write cannot be retracted, but it cannot acknowledge a changed target.
        try { guarded.read();fail("New request used an old target") } catch (_: IdentitySyncConfigurationChangedException) { }
    }
}
