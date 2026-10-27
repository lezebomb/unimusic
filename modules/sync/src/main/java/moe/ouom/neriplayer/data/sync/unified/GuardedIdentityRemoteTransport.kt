package moe.ouom.neriplayer.data.sync.unified

import java.io.IOException

class IdentitySyncConfigurationChangedException : IOException("Identity sync configuration changed")

/** Check both sides of a request; old target results cannot acknowledge a new configuration. */
class GuardedIdentityRemoteTransport<V>(
    private val delegate: UnifiedIdentityRemoteTransport<V>,
    private val isCurrent: () -> Boolean
) : UnifiedIdentityRemoteTransport<V> {
    override suspend fun read(): RemoteIdentitySnapshot<V> {
        checkCurrent()
        return delegate.read().also { checkCurrent() }
    }
    override suspend fun compareAndSet(expected: V, bytes: ByteArray): Boolean {
        checkCurrent()
        return delegate.compareAndSet(expected, bytes).also { checkCurrent() }
    }
    private fun checkCurrent() {
        if (!isCurrent()) throw IdentitySyncConfigurationChangedException()
    }
}
