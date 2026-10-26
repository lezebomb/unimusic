package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.core.player.policy.source.RecordingMatchPolicy
import moe.ouom.neriplayer.core.player.policy.source.RecordingMetadata
import moe.ouom.neriplayer.core.player.policy.source.SourceFailure
import org.junit.Assert.*
import org.junit.Test

class RememberedSourceMetadataTest {
    @Test fun freshNativeIdentityAndAliasVersionMustBothMatch() {
        val studio = """{"code":200,"songs":[{"id":42,"name":"晴天","ar":[{"id":6452,"name":"周杰伦"}],"dt":269000,"al":{"name":"叶惠美"}}]}"""
        val expected = RecordingMetadata("晴天", "周杰伦", 269_000)
        assertNull(neteaseDetailsAccessFailure(studio))
        assertEquals(SourceFailure.AUTH_REQUIRED, neteaseDetailsAccessFailure("""{"code":301}"""))
        assertEquals(SourceFailure.UNAVAILABLE, neteaseDetailsAccessFailure("""{"code":503}"""))
        assertTrue(RecordingMatchPolicy.assess(expected, requireNotNull(refreshedNeteaseSource(studio, "42")).second).accepted)
        assertNull(refreshedNeteaseSource(studio, "43"))
        for (key in listOf("alia", "alias", "tns", "transNames")) {
            val live = studio.replace("\"dt\":", "\"$key\":[\"晴天（Ｌｉｖｅ）\"],\"dt\":")
            assertFalse(RecordingMatchPolicy.assess(expected, requireNotNull(refreshedNeteaseSource(live, "42")).second).accepted)
        }
    }
}
