package moe.ouom.neriplayer.common.logging

import org.junit.Assert.*
import org.junit.Test

class NPLogPrivacyTest {
    @Test fun credentialsContactsAndSignedResourcesAreAbsentFromDiagnosticText() {
        val cases = listOf(
            """response {"password":"fixture-password","captcha":"123456","access_token":"fixture-token"} status=200""",
            "reason=AUTH_REQUIRED\nCookie: MUSIC_U=fixture-cookie; unknownCookie=fixture-value\nmore private payload",
            "Authorization: Bearer fixture-auth",
            "request Bearer fixture-bearer after failure",
            "currentUrl=https://user:fixture-userinfo@media.invalid/private-token/audio?sign=fixture-signature",
            "contact person@example.invalid 13900000000",
            """{\"password\":\"encoded secret with spaces\"}""",
            "code=654321 status=200",
            "123456"
        )
        val output = cases.map(NPLogPrivacy::sanitize).joinToString("\n")
        listOf("fixture-password", "123456", "fixture-token", "fixture-cookie", "fixture-value", "fixture-auth",
            "fixture-bearer", "fixture-userinfo", "private-token", "fixture-signature", "person@example.invalid",
            "13900000000", "encoded secret", "654321").forEach { assertFalse("Sensitive fixture survived", output.contains(it)) }
        assertTrue(output.contains("AUTH_REQUIRED"))
        assertTrue(output.contains("status=200"))
    }

    @Test fun boundedSanitizationHandlesTruncatedQuotedValuesWithoutChangingNormalDiagnostics() {
        val longSecret = "password=\"" + "z".repeat(NPLogPrivacy.MAX_INPUT_CHARS * 2)
        val result = NPLogPrivacy.sanitize(longSecret)
        assertFalse(result.contains("z".repeat(32)))
        assertTrue(result.endsWith("[truncated]"))
        assertEquals("source=netease status=200 elapsedMs=1200 count=3", NPLogPrivacy.sanitize("source=netease status=200 elapsedMs=1200 count=3"))
        assertEquals("code=200", NPLogPrivacy.sanitize("code=200"))
        assertFalse(NPLogPrivacy.sanitize("content://private/documents/token").contains("private"))
    }
}
