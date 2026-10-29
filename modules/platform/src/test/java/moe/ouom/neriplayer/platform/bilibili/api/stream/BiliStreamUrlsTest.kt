package moe.ouom.neriplayer.platform.bilibili.api.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliStreamUrlsTest {
    @Test fun isBiliStreamHostRejectsLookalikeDomainsAndNonWebSchemes() {
        for (host in listOf("bilivideo.com.attacker.example", "evilbilivideo.com", "bilivideo.evil", "x.mountaintoys.cn.evil")) {
            assertFalse(host, isBiliStreamHost(host))
            assertFalse(host, isBiliStreamUrl("https://$host/audio.m4s"))
        }
        assertFalse(isBiliStreamUrl("https://upos.bilivideo.com@attacker.example/audio.m4s"))
        assertFalse(isBiliStreamUrl("https://user@upos.bilivideo.com/audio.m4s"))
        assertFalse(isBiliStreamUrl("ftp://upos.bilivideo.com/audio.m4s"))
        assertTrue(isBiliStreamUrl("https://UPOS.EXAMPLE.BILIVIDEO.COM/audio.m4s"))
        assertTrue(isBiliStreamUrl("http://mcdn.bilivideo.cn:8082/audio.m4s"))
    }

    @Test
    fun prioritizeBiliStreamUrls_prefersBilivideoHostsOverMountaintoys() {
        val prioritized = prioritizeBiliStreamUrls(
            primaryUrl = "https://b-demo.edge.mountaintoys.cn/upgcxcode/demo.m4s",
            backupUrls = listOf(
                "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/demo.m4s",
                "https://xy123x45x67x89xy.mcdn.bilivideo.cn:8082/v1/resource/demo.m4s"
            )
        )

        assertEquals(
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/demo.m4s",
            prioritized.first()
        )
        assertTrue(prioritized.last().contains("mountaintoys.cn"))
    }

    @Test
    fun isBiliStreamHost_matchesMountaintoysEdgeDomain() {
        assertTrue(isBiliStreamHost("b-demo.edge.mountaintoys.cn"))
        assertTrue(isBiliStreamUrl("https://b-demo.edge.mountaintoys.cn/upgcxcode/demo.m4s"))
    }
}
