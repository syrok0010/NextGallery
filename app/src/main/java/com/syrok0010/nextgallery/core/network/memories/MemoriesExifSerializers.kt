package com.syrok0010.nextgallery.core.network.memories

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Serializers for Memories ExifTool JSON tags, where numeric values may arrive either as JSON
 * numbers or as formatted strings (e.g. `"6.3 mm"`, `"1/100"`, `"250"`).
 */
@OptIn(ExperimentalSerializationApi::class)
internal object ExifDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ExifDouble", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): Double? {
        val primitive = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: return runCatching { decoder.decodeDouble() }.getOrNull()
        if (primitive is JsonNull) return null
        primitive.doubleOrNull?.takeIf { it.isFinite() }?.let { return it }
        val raw = primitive.contentOrNull?.trim() ?: return null
        return EXIF_NUMBER_PATTERN.find(raw)?.value?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object ExifIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ExifInt", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): Int? {
        val primitive = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: return runCatching { decoder.decodeInt() }.getOrNull()
        if (primitive is JsonNull) return null
        primitive.intOrNull?.takeIf { it > 0 }?.let { return it }
        val raw = primitive.contentOrNull?.trim() ?: return null
        return EXIF_NUMBER_PATTERN.find(raw)?.value?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal object ExifExposureSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ExifExposure", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): Double? {
        val primitive = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
            ?: return runCatching { decoder.decodeDouble() }.getOrNull()
        if (primitive is JsonNull) return null
        primitive.doubleOrNull?.takeIf { it.isFinite() && it > 0.0 }?.let { return it }
        val raw = primitive.contentOrNull?.trim() ?: return null
        val fraction = EXIF_FRACTION_PATTERN.find(raw)
        if (fraction != null) {
            val numerator = fraction.groupValues[1].toDoubleOrNull()
            val denominator = fraction.groupValues[2].toDoubleOrNull()
            if (numerator != null && denominator != null && denominator > 0.0) {
                return (numerator / denominator).takeIf { it.isFinite() && it > 0.0 }
            }
        }
        return EXIF_NUMBER_PATTERN
            .find(
                raw,
            )?.value
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

private val EXIF_NUMBER_PATTERN = Regex("""-?\d+(?:\.\d+)?""")
private val EXIF_FRACTION_PATTERN = Regex("""(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)""")
