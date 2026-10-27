package moe.ouom.neriplayer.platform.qqmusic.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicAccountClient
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicCredential
import moe.ouom.neriplayer.platform.qqmusic.api.auth.QQMusicLoginStatus
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QQMusicAuthRepositoryTest {
    private val credential = QQMusicCredential.fromCookieHeaders(listOf("uin=123456; qm_keyst=Q_H_L_fixture"))!!

    @Test fun lateAudioAddressCannotSurviveSignOut() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("qq-audio-race", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().body!!.let { body -> okio.Buffer().also(body::writeTo).readUtf8() }.contains("get_song_detail_yqq"))
                """{"code":0,"detail":{"code":0,"data":{"track_info":{"mid":"003OUlho2HcRHC","title":"Song","interval":200,"singer":[{"name":"Artist"}],"file":{"media_mid":"00000000000000"}}}}}"""
            else {
                started.countDown(); check(release.await(5, TimeUnit.SECONDS))
                """{"code":0,"audio":{"code":0,"data":{"midurlinfo":[{"songmid":"003OUlho2HcRHC","filename":"M50000000000000000.mp3","result":0,"purl":"M50000000000000000.mp3?vkey=fixture"}]}}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val repository = QQMusicAuthRepository(context, preferences = prefs)
        assertTrue(repository.save(credential, repository.generation()))
        val result = async(Dispatchers.IO) { repository.resolveAudio("003OUlho2HcRHC",
            moe.ouom.neriplayer.platform.qqmusic.api.QQMusicAudioClient(client)) }
        try { assertTrue(started.await(5, TimeUnit.SECONDS)); repository.clear() }
        finally { release.countDown() }
        assertEquals(moe.ouom.neriplayer.data.model.unified.SourceCheckStatus.CONTEXT_CHANGED, result.await().status)
        assertNull(result.await().url)
    }

    @Test fun reopeningDoesNotPretendTheSessionWasServerVerified() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("qq-auth-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val repository = QQMusicAuthRepository(context, preferences = prefs)
        assertTrue(repository.save(credential, repository.generation()))
        val reopened = QQMusicAuthRepository(context, preferences = prefs)
        assertEquals("123456", reopened.state.value.accountId)
        assertEquals(repository.generation(), reopened.generation())
        assertEquals(QQMusicLoginStatus.SAVED_UNVERIFIED, reopened.state.value.status)
        val revision = repository.generation()
        repository.clear()
        assertFalse(repository.save(credential, revision)) // Old activity result must not undo sign-out.
        val signedOut = QQMusicAuthRepository(context, preferences = prefs)
        assertFalse(signedOut.save(credential, revision)) // The tombstone also survives process recreation.
        assertEquals(QQMusicLoginStatus.MISSING, repository.state.value.status)
    }

    @Test fun lateServerResponseCannotUndoSignOut() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("qq-race-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"account":{"code":0,"data":{"uin":"123456"}}}""".toResponseBody()).build()
        }.build()
        val repository = QQMusicAuthRepository(context, QQMusicAccountClient(client), prefs)
        assertTrue(repository.save(credential, repository.generation()))
        val result = async(Dispatchers.IO) { repository.check() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repository.clear()
        } finally { release.countDown() }
        assertEquals(QQMusicLoginStatus.CONTEXT_CHANGED, result.await().status)
        assertEquals(QQMusicLoginStatus.MISSING, repository.state.value.status)
    }
}
