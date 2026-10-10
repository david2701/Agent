package app.kairo.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

val KairoJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

/** JSON de la API pública: nulos explícitos para que los clientes vean todos los campos. */
val ApiJson = Json(KairoJson) { explicitNulls = true }

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = parseInstant(decoder.decodeString())
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString().take(10))
}

object UUIDSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** Acepta ISO-8601 con zona (`2026-10-11T09:00:00-05:00`, `...Z`). */
fun parseInstant(s: String): Instant = try {
    Instant.parse(s)
} catch (_: Exception) {
    java.time.OffsetDateTime.parse(s).toInstant()
}

// --- Acceso cómodo a JsonObject (argumentos de herramientas, settings) ---

fun JsonObject.str(key: String): String? {
    val v = this[key]
    if (v == null || v is JsonNull) return null
    return (v as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
}

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.content.toIntOrNull() }
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }
fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }
fun JsonObject.strList(key: String): List<String>? = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
    is JsonPrimitive -> if (v is JsonNull) null else v.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    else -> null
}
fun JsonObject.intList(key: String): List<Int>? = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
    is JsonPrimitive -> v.intOrNull?.let { listOf(it) }
    else -> null
}

fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonElement.primitiveContent(): String = jsonPrimitive.content
