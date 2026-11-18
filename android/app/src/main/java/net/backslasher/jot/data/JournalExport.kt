package net.backslasher.jot.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.backslasher.jot.schema.JournalSchema

/**
 * Builds the "Export JSON" document: the schema plus every non-deleted entry.
 *
 * ```
 * { "format": "jot-export", "formatVersion": 1, "exportedAt": <millis>,
 *   "schema": {...}, "entries": [ {id, date, values, createdAt, updatedAt}, ... ] }
 * ```
 */
object JournalExport {
    const val FORMAT = "jot-export"
    const val FORMAT_VERSION = 1

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun build(schema: JournalSchema, entries: List<Entry>, exportedAt: Long): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put("formatVersion", FORMAT_VERSION)
        put("exportedAt", exportedAt)
        put("schema", json.encodeToJsonElement(JournalSchema.serializer(), schema))
        put("entries", buildJsonArray {
            entries.forEach { e ->
                add(buildJsonObject {
                    put("id", e.id)
                    put("date", e.date.toString())
                    put("values", e.values)
                    put("createdAt", e.createdAt)
                    put("updatedAt", e.updatedAt)
                })
            }
        })
    }

    fun toJsonString(schema: JournalSchema, entries: List<Entry>, exportedAt: Long): String =
        json.encodeToString(JsonObject.serializer(), build(schema, entries, exportedAt))
}
