package moe.ouom.neriplayer.core.player.policy.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Every child signals completion, even if an adapter cancels itself. The caller owns the total budget. */
suspend fun <T : Any> racePlaybackSources(tasks: List<suspend () -> T?>): T? = coroutineScope {
    if (tasks.isEmpty()) return@coroutineScope null
    val completed = Channel<T?>(tasks.size)
    val jobs = tasks.map { task -> launch {
        var value: T? = null
        try { value = task() }
        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
        catch (_: Exception) { /* A failed provider does not invalidate the other candidates. */ }
        finally { completed.trySend(value) }
    } }
    try {
        repeat(tasks.size) { completed.receive()?.let { return@coroutineScope it } }
        null
    } finally {
        jobs.forEach { it.cancel() }
        completed.close()
    }
}
