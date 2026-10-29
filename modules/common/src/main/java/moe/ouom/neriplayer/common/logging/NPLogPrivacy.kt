package moe.ouom.neriplayer.common.logging

/** Forward-only diagnostic protection. Never keep auth payloads or signed media URLs in log sinks. */
internal object NPLogPrivacy {
    internal const val MAX_INPUT_CHARS = 32_768
    private const val REDACTED = "[redacted]"
    private val urls = Regex("""(?i)\b(?:https?|file|content)://[^\s"'<>]+""")
    private val bearer = Regex("""(?i)\bBearer[ \t]+[A-Za-z0-9._~+/=-]+""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")
    private val email = Regex("""(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""")
    private val phone = Regex("""(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)""")
    // Once a structured auth/contact payload starts, its remainder is unnecessary for diagnostics.
    private val payload = Regex("""(?is)((?<![A-Za-z0-9_])(?:\\*["'])?(?:cookies?|set-cookie|authorization|credentials|loginPayload|phone|mobile|email|手机号|手机号码|邮箱)(?:\\*["'])?\s*[:=：]\s*)[\s\S]*""")
    private val secretNames = "(?:[a-z0-9_]{0,64}(?:token|secret|password|passwd)[a-z0-9_]{0,64}|pwd|pin|pinCode|otp|smsCode|captcha|verificationCode|verification_code|verifyCode|code|MUSIC_U|MUSIC_A|SESSDATA|bili_jct|csrf|__csrf|encSecKey|NMTID|_ntes_nuid|apiKey|api_key|accessKey|accessKeyId|session|sessionId|sessionKey|signature|sign|验证码|密码|口令)"
    private val secretKey = Regex("""(?i)(?<![A-Za-z0-9_])(?:\\*["'])?($secretNames)(?:\\*["'])?\s*[:=：]\s*""")

    fun sanitize(raw: String): String {
        val clipped = raw.take(MAX_INPUT_CHARS)
        if (clipped.trim().matches(Regex("""\d{4,8}"""))) return REDACTED
        var text = payload.replace(clipped) { it.groupValues[1] + REDACTED }
        text = redactFields(text)
        text = urls.replace(text, "[url]")
        text = bearer.replace(text, "Bearer $REDACTED")
        text = jwt.replace(text, REDACTED)
        text = email.replace(text, "[email]")
        text = phone.replace(text, "[phone]")
        return text + if (raw.length > MAX_INPUT_CHARS) " [truncated]" else ""
    }

    private fun redactFields(text: String): String = buildString {
        var cursor = 0
        for (match in secretKey.findAll(text)) {
            if (match.range.first < cursor) continue
            val start = match.range.last + 1
            val first = text.getOrNull(start)
            var end = start
            if (first == '\\' || first == '{' || first == '[') {
                // Encoded JSON/structured secret fields are deliberately opaque rather than partially logged.
                end = text.length
            } else if (first == '"' || first == '\'') {
                end++
                while (end < text.length) {
                    if (text[end] == '\\') { end = (end + 2).coerceAtMost(text.length); continue }
                    if (text[end++] == first) break
                }
            } else {
                while (end < text.length && !text[end].isWhitespace() && text[end] !in ",;}]") end++
            }
            // Preserve ordinary service status codes; four-to-eight digit verification-like codes stay private.
            if (match.groupValues[1].equals("code", true)) {
                val value = text.substring(start, end).trim('"', '\'')
                if (!value.matches(Regex("""\d{4,8}"""))) continue
            }
            append(text, cursor, match.range.first)
            append(match.value)
            if (first == '"' || first == '\'') append(first)
            append(REDACTED)
            if (first == '"' || first == '\'') append(first)
            cursor = end
        }
        append(text, cursor, text.length)
    }
}
