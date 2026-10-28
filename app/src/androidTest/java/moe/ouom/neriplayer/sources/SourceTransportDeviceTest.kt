package moe.ouom.neriplayer.sources

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual, anonymous transport diagnosis. No account values, addresses or response bodies are emitted. */
@RunWith(AndroidJUnit4::class)
class SourceTransportDeviceTest {
    @Test fun diagnoseAnonymousSourceTransport() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("unimusicTransportProbe") == "true")
        val targets = listOf("KUWO" to "https://www.kuwo.cn/", "NETEASE" to "https://music.163.com/",
            "DIRECT_IP_CONTROL" to "https://1.1.1.1/")
        for ((label, url) in targets) {
            val started = SystemClock.elapsedRealtime()
            var stage = "START"
            val client = OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS)
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false).eventListener(object : EventListener() {
                    override fun dnsStart(call: Call, domainName: String) { stage = "DNS_STARTED" }
                    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { stage = "DNS_FINISHED" }
                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { stage = "TCP_STARTED" }
                    override fun secureConnectStart(call: Call) { stage = "TLS_STARTED" }
                    override fun secureConnectEnd(call: Call, handshake: Handshake?) { stage = "TLS_FINISHED" }
                    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { stage = "CONNECTED" }
                    override fun responseHeadersStart(call: Call) { stage = "RESPONSE_STARTED" }
                }).build()
            var status = -1
            var failure = "NONE"
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { status = it.code }
            } catch (error: IOException) {
                failure = error.javaClass.simpleName
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("transport_target", label); putString("transport_stage", stage)
                putString("failure_type", failure); putInt("http_status", status)
                putLong("elapsed_ms", SystemClock.elapsedRealtime() - started)
            })
        }
    }
}
