package moe.ouom.neriplayer.platform.bilibili.api.stream

import org.junit.Assert.*
import org.junit.Test

class BiliStreamCredentialPolicyTest {
    @Test fun savedCookiesUseOnlyActualEncryptedTrustedDestinations() {
        val original = linkedMapOf("cOoKiE" to "synthetic-old", "authorization" to "synthetic-auth", "Accept" to "audio/*")
        val trusted = buildBiliStreamRequestHeaders("https://upos.bilivideo.com/a.m4s", original, "synthetic-current")
        assertEquals("synthetic-current", trusted["Cookie"])
        assertEquals(1, trusted.keys.count { it.equals("cookie", true) })
        assertFalse(trusted.keys.any { it.equals("authorization", true) })
        val cleartext = buildBiliStreamRequestHeaders("http://mcdn.bilivideo.cn:8082/a.m4s", original, "synthetic-current")
        assertFalse(cleartext.keys.any { it.equals("cookie", true) || it.equals("authorization", true) })
        assertEquals("audio/*", cleartext["Accept"])
        for (url in listOf("https://bilivideo.com.attacker.example/a.m4s", "https://cdn.kuwo.cn/a.mp3")) {
            assertEquals(emptyMap<String, String>(), buildBiliStreamRequestHeaders(url, emptyMap(), "synthetic-current"))
        }
        assertEquals("synthetic-old", original["cOoKiE"])
    }

}
