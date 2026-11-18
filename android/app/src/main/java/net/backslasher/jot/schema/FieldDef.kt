package net.backslasher.jot.schema

import kotlinx.serialization.Serializable

/**
 * The kinds of field an entry can hold, and the JSON shape each one is stored as
 * in [net.backslasher.jot.data.Entry.values]:
 *
 *  - [CHECKBOX]: JSON boolean
 *  - [TEXT_MULTILINE]: JSON string
 *  - [MULTI_SELECT]: JSON array of option ids, in the order the user selected them
 */
@Serializable
enum class FieldType { CHECKBOX, TEXT_MULTILINE, MULTI_SELECT }

/**
 * One choice of a [FieldType.MULTI_SELECT] field.
 *
 * @param id stable identifier that is what gets stored; never rename an id, only its label.
 * @param color ARGB colour of the chip. Text on it is always drawn dark, so keep it light.
 */
@Serializable
data class FieldOption(
    val id: String,
    val label: String,
    val color: Long,
)

/**
 * Definition of one field of a journal entry. The entry form and the list rows
 * are rendered by iterating over [JournalSchema.fields].
 *
 * @param key stable key in the entry's values object; never rename.
 */
@Serializable
data class FieldDef(
    val key: String,
    val label: String,
    val type: FieldType,
    val options: List<FieldOption> = emptyList(),
) {
    fun option(id: String): FieldOption? = options.firstOrNull { it.id == id }
}

@Serializable
data class JournalSchema(
    val version: Int,
    val fields: List<FieldDef>,
) {
    init {
        require(fields.map { it.key }.toSet().size == fields.size) { "Duplicate field keys" }
        fields.forEach { f ->
            require(f.options.map { it.id }.toSet().size == f.options.size) { "Duplicate option ids in ${f.key}" }
        }
    }

    fun field(key: String): FieldDef? = fields.firstOrNull { it.key == key }
}
