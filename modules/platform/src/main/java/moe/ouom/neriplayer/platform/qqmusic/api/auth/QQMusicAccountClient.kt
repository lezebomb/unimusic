package moe.ouom.neriplayer.platform.qqmusic.api.auth

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.io.readBytesLimited
import moe.ouom.neriplayer.network.http.awaitResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

enum class QQMusicLoginStatus { MISSING, SAVED_UNVERIFIED, VERIFIED, EXPIRED, UNAVAILABLE, CONTEXT_CHANGED }
data class QQMusicAccountCheck(val status: QQMusicLoginStatus, val nickname: String? = null)

/** Requests only the current authenticated identity. No public profile is used as proof of login. */
class QQMusicAccountClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build()) {
    suspend fun check(credential: QQMusicCredential): QQMusicAccountCheck {
        return try {
            val body = JSONObject().put("comm", JSONObject().put("ct", 24).put("cv", 0)
                .put("uin", credential.accountId).put("authst", credential.musicKey)
                .put("tmeLoginType", credential.loginType).put("format", "json"))
                .put("account", JSONObject().put("module", "music.UserInfo.userInfoServer")
                    .put("method", "GetLoginUserInfo").put("param", JSONObject()))
            client.newCall(Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .header("Cookie", credential.cookieHeader()).header("Referer", "https://y.qq.com/")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
                .awaitResponse { response ->
                    if (!response.isSuccessful) throw IOException("QQ Music account HTTP ${response.code}")
                    parseQQMusicAccountCheck(response.body.byteStream().readBytesLimited(128 * 1024L)
                        .toString(Charsets.UTF_8), credential.accountId)
                }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { QQMusicAccountCheck(QQMusicLoginStatus.UNAVAILABLE) }
    }
}

internal fun parseQQMusicAccountCheck(raw: String, expectedId: String): QQMusicAccountCheck {
    val root = JSONObject(raw)
    val result = root.optJSONObject("account") ?: return QQMusicAccountCheck(QQMusicLoginStatus.UNAVAILABLE)
    val codes = listOf(root.optInt("code", -1), result.optInt("code", -1))
    if (codes.any { it in setOf(1000, 104400, 104401, 20278) }) return QQMusicAccountCheck(QQMusicLoginStatus.EXPIRED)
    if (codes.any { it != 0 }) return QQMusicAccountCheck(QQMusicLoginStatus.UNAVAILABLE)
    val data = result.optJSONObject("data") ?: return QQMusicAccountCheck(QQMusicLoginStatus.SAVED_UNVERIFIED)
    val identities = listOfNotNull(data, data.optJSONObject("userInfo"), data.optJSONObject("user_info"))
    val id = identities.firstNotNullOfOrNull { info ->
        listOf("musicid", "musicId", "str_musicid", "uin").mapNotNull { key ->
            info.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        }.firstOrNull()
    }
    if (id != expectedId) return QQMusicAccountCheck(QQMusicLoginStatus.SAVED_UNVERIFIED)
    val name = identities.firstNotNullOfOrNull { info ->
        listOf("nickname", "nick", "name").mapNotNull { key ->
            (info.opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
        }.firstOrNull()
    }
    return QQMusicAccountCheck(QQMusicLoginStatus.VERIFIED, name)
}
