package moe.ouom.neriplayer.core.network.stream

import moe.ouom.neriplayer.platform.bilibili.api.stream.isBiliStreamUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Check actual redirect and descendant destinations, including inherited manifest/segment headers. */
class BiliStreamCredentialInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = chain.call().request()
        val biliScoped = isBiliStreamUrl(original.url.toString()) || isBiliWebHost(original.url.host) ||
            hasBiliCredentialScope(original) || hasBiliCredentialScope(request)
        val trustedDestination = request.url.isHttps && request.url.username.isEmpty() && request.url.password.isEmpty() &&
            (isBiliStreamUrl(request.url.toString()) || isBiliWebHost(request.url.host))
        if (!biliScoped || trustedDestination) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder()
            .removeHeader("Cookie").removeHeader("Cookie2").removeHeader("Authorization").build())
    }
}

private val BILI_COOKIE_NAMES = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "buvid3", "buvid4")

private fun isBiliWebHost(host: String): Boolean = host == "bilibili.com" || host.endsWith(".bilibili.com")

private fun hasBiliCredentialScope(request: Request): Boolean {
    val refererHost = request.header("Referer")?.toHttpUrlOrNull()?.host.orEmpty()
    if (isBiliWebHost(refererHost)) return true
    return listOf("Cookie", "Cookie2").any { header -> request.headers.values(header).any { value ->
        value.split(';').any { it.substringBefore('=').trim() in BILI_COOKIE_NAMES }
    } }
}
