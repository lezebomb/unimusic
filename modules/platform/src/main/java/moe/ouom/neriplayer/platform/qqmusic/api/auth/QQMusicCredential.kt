package moe.ouom.neriplayer.platform.qqmusic.api.auth

import org.json.JSONObject

/** Only QQ Music web-session fields; never includes a password or unrelated QQ cookies. */
class QQMusicCredential private constructor(val accountId: String, private val values: Map<String, String>) {
    internal val musicKey: String get() = values["qm_keyst"].orEmpty().ifBlank { values["qqmusic_key"].orEmpty() }
    internal val loginType: Int get() = if (values["login_type"] == "2" || musicKey.startsWith("W_X_")) 2 else 1
    internal fun cookieHeader(): String = values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    fun toStorageJson(): String = JSONObject(values).toString()
    override fun toString(): String = "QQMusicCredential(redacted)"

    companion object {
        private val allowed = setOf("uin", "wxuin", "qqmusic_key", "qm_keyst", "login_type")

        fun fromCookieHeaders(headers: List<String>): QQMusicCredential? {
            val cookies = linkedMapOf<String, String>()
            headers.forEach { header -> header.split(';').forEach { part ->
                val key = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (key in allowed && value.isNotBlank() && value.length <= 4096 &&
                    value.none { it <= ' ' || it == ';' || it == '\u007f' }) cookies[key] = value
            } }
            val musicKey = cookies["qm_keyst"].orEmpty().ifBlank { cookies["qqmusic_key"].orEmpty() }
            if (musicKey.isBlank()) return null
            val isWeChat = cookies["login_type"] == "2" || musicKey.startsWith("W_X_")
            val id = (if (isWeChat) cookies["wxuin"].orEmpty().ifBlank { cookies["uin"].orEmpty() }
                else cookies["uin"].orEmpty()).removePrefix("o").trimStart('0')
            if (id.isEmpty() || id.length > 20 || id.any { it !in '0'..'9' }) return null
            return QQMusicCredential(id, cookies.toMap())
        }

        fun fromStorageJson(raw: String): QQMusicCredential? {
            if (raw.length > 24_000) return null
            return runCatching {
                val root = JSONObject(raw)
                fromCookieHeaders(listOf(allowed.mapNotNull { key ->
                    (root.opt(key) as? String)?.let { "$key=$it" }
                }.joinToString("; ")))
            }.getOrNull()
        }
    }
}
