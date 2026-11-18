package net.backslasher.jot.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.util.UUID

/**
 * One day's journal entry.
 *
 * [values] is keyed by [net.backslasher.jot.schema.FieldDef.key]. It is kept as raw
 * JSON so that keys the current schema doesn't know (written by a newer version or
 * another client) survive a read-modify-write untouched, and so that a missing key
 * or a value of an unexpected type reads as the field's empty value.
 */
data class Entry(
    val id: String,
    val date: LocalDate,
    val values: JsonObject,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    fun bool(key: String): Boolean = values[key].asBoolean()
    fun text(key: String): String = values[key].asText()
    fun options(key: String): List<String> = values[key].asOptionIds()

    companion object {
        fun new(date: LocalDate, now: Long, id: String = UUID.randomUUID().toString()) =
            Entry(id = id, date = date, values = JsonObject(emptyMap()), createdAt = now, updatedAt = now)
    }
}

fun JsonElement?.asBoolean(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

fun JsonElement?.asText(): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""

fun JsonElement?.asOptionIds(): List<String> =
    (this as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?.distinct()
        ?: emptyList()

/** Returns a copy with [key] set; all other keys (including unknown ones) are preserved. */
fun JsonObject.with(key: String, value: JsonElement?): JsonObject =
    JsonObject(toMutableMap().apply { if (value == null || value is JsonNull) remove(key) else put(key, value) })

fun JsonObject.withBool(key: String, value: Boolean) = with(key, JsonPrimitive(value))
fun JsonObject.withText(key: String, value: String) = with(key, JsonPrimitive(value))
fun JsonObject.withOptions(key: String, ids: List<String>) = with(key, JsonArray(ids.map { JsonPrimitive(it) }))
