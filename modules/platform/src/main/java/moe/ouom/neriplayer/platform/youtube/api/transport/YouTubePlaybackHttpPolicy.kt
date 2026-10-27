package moe.ouom.neriplayer.platform.youtube.api.transport

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.OkHttpClient
import okhttp3.Request

// 脏 IP 下 429/503 退避基数与上限, 避免密集重试进一步拉高限流等级 (#Y5)
private const val RATE_LIMIT_BACKOFF_BASE_MS = 500L
private const val RATE_LIMIT_BACKOFF_MAX_MS = 5_000L



/**
 * 携带 HTTP 状态码与 Retry-After 的请求失败异常
 * 仍继承 IOException, 保持既有 catch(IOException) 与消息正则解析 (401/403/429) 兼容
 */
class YouTubeHttpStatusException(
    val statusCode: Int,
    val retryAfterMs: Long?,
    message: String
) : IOException(message)

/** 解析 Retry-After: 仅支持 delta-seconds 整数秒形式, HTTP-date 形式忽略并走指数退避 */
fun parseRetryAfterMs(headerValue: String?): Long? {
    val seconds = headerValue?.trim()?.toLongOrNull() ?: return null
    return if (seconds >= 0L) seconds * 1000L else null
}

/** 429/503 退避时长: 优先 Retry-After, 否则按累计命中次数指数退避, 统一封顶 */
fun rateLimitBackoffMs(error: Throwable?, priorHits: Int): Long? {
    val status = error as? YouTubeHttpStatusException ?: return null
    if (status.statusCode != 429 && status.statusCode != 503) {
        return null
    }
    status.retryAfterMs?.let { return it.coerceIn(0L, RATE_LIMIT_BACKOFF_MAX_MS) }
    return (RATE_LIMIT_BACKOFF_BASE_MS shl priorHits.coerceIn(0, 3))
        .coerceAtMost(RATE_LIMIT_BACKOFF_MAX_MS)
}

/** Cancel the underlying call, including response-body reads, when this stage or its caller expires. */
internal suspend fun OkHttpClient.executeYouTubePlaybackText(
    request: Request,
    timeoutMs: Long = 3_000
): String {
    require(timeoutMs > 0)
    return withTimeoutOrNull(timeoutMs) {
        newCall(request).awaitResponse { response ->
            if (!response.isSuccessful) {
                // Keep status-based recovery; response bodies can contain account or request details.
                throw YouTubeHttpStatusException(response.code, parseRetryAfterMs(response.header("Retry-After")),
                    "YouTube Music request failed: ${response.code}")
            }
            response.body.readTextWithLimit(YOUTUBE_TEXT_RESPONSE_MAX_BYTES)
        }
    } ?: throw SocketTimeoutException("YouTube playback request timed out")
}
