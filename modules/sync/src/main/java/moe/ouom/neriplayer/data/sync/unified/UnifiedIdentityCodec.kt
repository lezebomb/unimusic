package moe.ouom.neriplayer.data.sync.unified

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.data.model.unified.sync.UnifiedIdentityDataset

/** Bounded standalone document. Strict members prevent accidental credential-field expansion. */
object UnifiedIdentityCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    fun encode(data: UnifiedIdentityDataset): ByteArray {
        UnifiedIdentityValidation.validate(data)
        return json.encodeToString(data).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_BYTES) { "Unified identity document too large" }
        }
    }

    fun decode(bytes: ByteArray): UnifiedIdentityDataset {
        val text = validateWire(bytes, setOf("schemaVersion", "capability"))
        return json.decodeFromString<UnifiedIdentityDataset>(text).also(UnifiedIdentityValidation::validate)
    }

    internal fun validateWire(bytes: ByteArray, requiredRootFields: Set<String>): String {
        require(bytes.size <= MAX_BYTES) { "Unified identity document too large" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        JsonReader(StringReader(text)).use { reader ->
            inspect(reader, 0, requiredRootFields)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing unified identity data" }
        }
        return text
    }

    private fun inspect(reader: JsonReader, depth: Int, requiredRootFields: Set<String>) {
        require(depth <= 12) { "Unified identity document too deep" }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val names = hashSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name.length <= 128 && names.size < 64 && names.add(name)) { "Duplicate or oversized unified identity member" }
                    inspect(reader, depth + 1, requiredRootFields)
                }
                reader.endObject()
                if (depth == 0) require(names.containsAll(requiredRootFields)) { "Missing unified identity protocol" }
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var count = 0
                while (reader.hasNext()) {
                    require(++count <= UnifiedIdentityValidation.MAX_REVISIONS) { "Unified identity array too large" }
                    inspect(reader, depth + 1, requiredRootFields)
                }
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> require(reader.nextString().length <= 4096) { "Unified identity value too long" }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> throw IllegalArgumentException("Invalid unified identity JSON")
        }
    }
}
