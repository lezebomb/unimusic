package moe.ouom.neriplayer.platform.qqmusic.api.auth

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class QQMusicCredentialTest {
    @Test fun onlyMusicSessionFieldsAndTrustedWebNavigationAreAccepted() {
        val auth = QQMusicCredential.fromCookieHeaders(listOf("uin=o00123456; qm_keyst=Q_H_L_fixture; password=never-store; unrelated=value"))!!
        assertEquals("123456", auth.accountId)
        assertFalse(auth.toStorageJson().contains("password"))
        assertFalse(auth.toStorageJson().contains("unrelated"))
        assertFalse(auth.toString().contains("fixture"))
        assertEquals(auth.accountId, QQMusicCredential.fromStorageJson(auth.toStorageJson())?.accountId)
        val wechat = QQMusicCredential.fromCookieHeaders(listOf("wxuin=1234567890123456789; qm_keyst=W_X_fixture; login_type=2"))!!
        assertEquals("1234567890123456789", wechat.accountId)
        assertNull(QQMusicCredential.fromCookieHeaders(listOf("uin=0; qm_keyst=key")))
        assertNull(QQMusicCredential.fromCookieHeaders(listOf("uin=123; qm_keyst=bad\r\nheader")))
        assertTrue(isTrustedQQMusicLoginUrl("https://ssl.ptlogin2.qq.com/login"))
        for (url in listOf("http://y.qq.com/", "https://y.qq.com.evil.test/", "https://user:password@y.qq.com/",
            "file:///data/private", "intent://qq")) assertFalse(isTrustedQQMusicLoginUrl(url))
    }

    @Test fun serverIdentityIsRequiredAndCredentialChecksUseOnlyTheQQGateway() = runBlocking {
        val auth = QQMusicCredential.fromCookieHeaders(listOf("uin=123456; qm_keyst=Q_H_L_fixture"))!!
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("u.y.qq.com", chain.request().url.host)
            assertEquals("https", chain.request().url.scheme)
            assertNotNull(chain.request().header("Cookie"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"account":{"code":0,"data":{"musicid":"123456","nickname":"Fixture"}}}""".toResponseBody()).build()
        }.build()
        assertEquals(QQMusicLoginStatus.VERIFIED, QQMusicAccountClient(client).check(auth).status)
        assertEquals(QQMusicLoginStatus.SAVED_UNVERIFIED,
            parseQQMusicAccountCheck("""{"code":0,"account":{"code":0,"data":{"uin":"999"}}}""", "123456").status)
        assertEquals(QQMusicLoginStatus.SAVED_UNVERIFIED,
            parseQQMusicAccountCheck("""{"code":0,"account":{"code":0,"data":{"profile":{"uin":"123456"}}}}""", "123456").status)
        assertEquals(QQMusicLoginStatus.EXPIRED,
            parseQQMusicAccountCheck("""{"code":0,"account":{"code":1000}}""", "123456").status)
    }
}
