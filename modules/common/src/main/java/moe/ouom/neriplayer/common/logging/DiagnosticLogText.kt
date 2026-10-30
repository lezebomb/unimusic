package moe.ouom.neriplayer.common.logging

import java.io.Reader
import java.io.StringWriter
import java.io.Writer

data class DiagnosticLogContent(val text: String, val truncated: Boolean)

/** Read-only protection for older logs and crash reports; original local evidence is never rewritten. */
object DiagnosticLogText {
    const val MAX_VIEW_INPUT_CHARS = 1_048_576
    private const val OMITTED_LINE = "[overlong diagnostic line omitted]"
    private const val TRUNCATED = "[diagnostic text truncated]"

    fun read(input: Reader, maxInputChars: Int = MAX_VIEW_INPUT_CHARS): DiagnosticLogContent {
        require(maxInputChars in 1..MAX_VIEW_INPUT_CHARS)
        val output = StringWriter()
        val truncated = sanitize(input, output, maxInputChars)
        return DiagnosticLogContent(output.toString(), truncated)
    }

    /** Export streams the whole document using bounded lines, without loading it all into memory. */
    fun copySanitized(input: Reader, output: Writer) {
        sanitize(input, output, null)
    }

    private fun sanitize(input: Reader, output: Writer, maxInputChars: Int?): Boolean {
        val reader = input.buffered()
        val line = StringBuilder()
        var overlong = false
        var omitted = false
        var consumed = 0L
        fun emitLine(newline: Boolean) {
            output.write(if (overlong) OMITTED_LINE else NPLogPrivacy.sanitize(line.toString()))
            if (newline) output.write("\n")
            omitted = omitted || overlong
            line.setLength(0)
            overlong = false
        }
        while (true) {
            val value = reader.read()
            if (value == -1) {
                if (line.isNotEmpty() || overlong) emitLine(false)
                return omitted
            }
            if (maxInputChars != null && consumed >= maxInputChars) {
                // Never display a truncated credential field as an ordinary continuation fragment.
                output.write(TRUNCATED)
                return true
            }
            consumed++
            val character = value.toChar()
            if (character == '\n') emitLine(true)
            else if (!overlong) {
                if (line.length == NPLogPrivacy.MAX_INPUT_CHARS) {
                    overlong = true
                    line.setLength(0)
                } else if (character != '\r') line.append(character)
            }
        }
    }
}
