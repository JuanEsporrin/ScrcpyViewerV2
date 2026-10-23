package com.programmersbox.scrcpyviewer.presets

import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val description: String = "",
    @Serializable(with = OptionValuesSerializer::class) val options: OptionValues,
)

internal fun OptionValues.toJson(): JsonObject = JsonObject(
    entries.mapValues { (_, v) -> if (v == null) JsonPrimitive(true) else JsonPrimitive(v) },
)

/** `true` becomes a flag (null value); a string stays a string; `false` and other types are dropped. */
internal fun JsonObject.toOptionValues(): OptionValues {
    val map = LinkedHashMap<String, String?>()
    for ((flag, el) in this) {
        val p = el as? JsonPrimitive ?: continue
        if (p is JsonNull) continue
        if (p.isString) map[flag] = p.content
        else if (p.booleanOrNull == true) map[flag] = null
    }
    return OptionValues(map)
}

/**
 * Serializes [OptionValues] as a JSON object: boolean flags are `true`, valued options are strings. Flags that are
 * not in [OptionCatalog] are dropped when reading.
 */
object OptionValuesSerializer : KSerializer<OptionValues> {
    private val delegate = JsonObject.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: OptionValues) {
        require(encoder is JsonEncoder) { "OptionValuesSerializer works with JSON only" }
        encoder.encodeJsonElement(value.toJson())
    }

    override fun deserialize(decoder: Decoder): OptionValues {
        require(decoder is JsonDecoder) { "OptionValuesSerializer works with JSON only" }
        val obj = decoder.decodeJsonElement() as? JsonObject
            ?: throw IllegalArgumentException("options must be an object")
        // Options the catalog no longer has (e.g. a retired one) are dropped: they could not be edited away.
        return JsonObject(obj.filterKeys { OptionCatalog.byFlag(it) != null }).toOptionValues()
    }
}
