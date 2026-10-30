package moe.ouom.neriplayer.common.logging

import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

class DiagnosticLogTextTest {
    @Test fun legacyLogsAreSanitizedForViewingAndStreamingWithoutLosingUsefulDiagnostics() {
        val raw = "Exception: request https://audio.example/track?signature=synthetic-signature\n" +
            "Cookie: MUSIC_U=synthetic-session\n" +
            "email=fixture@example.invalid\n" +
            "otp=123456\n" +
            "at app.Player.resolve(Player.kt:42)\nHTTP code=200\n"
        val view = DiagnosticLogText.read(StringReader(raw))
        val export = StringWriter().also { DiagnosticLogText.copySanitized(StringReader(raw), it) }.toString()
        assertEquals(view.text, export)
        assertFalse(view.truncated)
        for (secret in listOf("synthetic-signature", "synthetic-session", "fixture@example.invalid", "123456")) {
            assertFalse(secret, export.contains(secret))
        }
        assertTrue(export.contains("Player.kt:42"))
        assertTrue(export.contains("HTTP code=200"))
        assertTrue(raw.contains("synthetic-session"))
    }

    @Test fun oversizedLinesAndPartialFieldsNeverEscapeBoundedViewsOrStreamingExports() {
        val hugeLine = "x".repeat(NPLogPrivacy.MAX_INPUT_CHARS + 1) + "Cookie=synthetic-tail"
        val raw = "$hugeLine\nNormal diagnostic\n"
        val export = StringWriter().also { DiagnosticLogText.copySanitized(StringReader(raw), it) }.toString()
        assertTrue(export.startsWith("[overlong diagnostic line omitted]\n"))
        assertTrue(export.endsWith("Normal diagnostic\n"))
        assertFalse(export.contains("synthetic-tail"))
        val view = DiagnosticLogText.read(StringReader("Normal\nCookie=synthetic-secret"), maxInputChars = 15)
        assertTrue(view.truncated)
        assertEquals("Normal\n[diagnostic text truncated]", view.text)
        assertEquals("plain", DiagnosticLogText.read(StringReader("plain"), maxInputChars = 5).text)
    }
}
