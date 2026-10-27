package moe.ouom.neriplayer.platform.youtube.api.transport

import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class YouTubePlaybackHttpCancellationTest {
    private val request = Request.Builder().url("https://music.youtube.com/").build()

    @Test fun stageDeadlineCancelsTheUnderlyingCall() = runBlocking {
        val gate = CountDownLatch(1)
        val observed = AtomicReference<Call>()
        val client = blockedClient(gate, observed)
        try {
            val failure = runCatching { client.executeYouTubePlaybackText(request, timeoutMs = 200) }.exceptionOrNull()
            assertTrue(failure is SocketTimeoutException)
            assertTrue(requireNotNull(observed.get()).isCanceled())
        } finally { gate.countDown(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun callerCancellationRemainsCancellation() = runBlocking {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val observed = AtomicReference<Call>()
        val client = blockedClient(gate, observed, entered)
        try {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                client.executeYouTubePlaybackText(request)
                fail("Cancelled request must not return a body")
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(1, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(requireNotNull(observed.get()).isCanceled())
        } finally { gate.countDown(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun responseStatusKeepsRecoveryFieldsWithoutEchoingBody() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429)
                .message("limited").header("Retry-After", "2").body("private-request-data".toResponseBody()).build()
        }.build()
        try {
            val failure = runCatching { client.executeYouTubePlaybackText(request) }.exceptionOrNull()
            assertTrue(failure is YouTubeHttpStatusException)
            failure as YouTubeHttpStatusException
            assertEquals(429, failure.statusCode)
            assertEquals(2_000L, failure.retryAfterMs)
            assertFalse(failure.message.orEmpty().contains("private-request-data"))
        } finally { client.dispatcher.executorService.shutdown() }
    }

    private fun blockedClient(gate: CountDownLatch, observed: AtomicReference<Call>, entered: CountDownLatch? = null) =
        OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { observed.set(call) }
        }).addInterceptor { chain ->
            entered?.countDown()
            check(gate.await(2, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("payload".toResponseBody()).build()
        }.build()
}
