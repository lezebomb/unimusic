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
        val biliScoped = isBiliStreamUrl(original.url.toString()) ||
            hasBiliCredentialScope(original) || hasBiliCredentialScope(request)
        if (!biliScoped || (request.url.isHttps && isBiliStreamUrl(request.url.toString()))) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder()
            .removeHeader("Cookie").removeHeader("Cookie2").removeHeader("Authorization").build())
    }
}

private val BILI_COOKIE_NAMES = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "buvid3", "buvid4")

private fun hasBiliCredentialScope(request: Request): Boolean {
    val refererHost = request.header("Referer")?.toHttpUrlOrNull()?.host.orEmpty()
    if (refererHost == "bilibili.com" || refererHost.endsWith(".bilibili.com")) return true
    return listOf("Cookie", "Cookie2").any { header -> request.headers.values(header).any { value ->
        value.split(';').any { it.substringBefore('=').trim() in BILI_COOKIE_NAMES }
    } }
}
