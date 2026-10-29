package moe.ouom.neriplayer.core.network.stream

import moe.ouom.neriplayer.platform.bilibili.api.stream.isBiliStreamUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Network interceptor: OkHttp can carry an explicit Cookie over redirects; check every actual hop. */
class BiliStreamCredentialInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origin = chain.call().request().url.toString()
        if (!isBiliStreamUrl(origin) || (request.url.isHttps && isBiliStreamUrl(request.url.toString()))) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder()
            .removeHeader("Cookie").removeHeader("Cookie2").removeHeader("Authorization").build())
    }
}
