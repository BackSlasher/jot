package net.backslasher.jot.schema

/**
 * The journal's fields. This is the one place to add a field: the entry form,
 * list rows and export all iterate over it. Existing entries simply lack the new
 * key and are shown with the field's empty value.
 */
object DefaultSchema {
    const val MOOD = "mood"
    const val NAP = "nap"
    const val WORKOUT = "workout"
    const val NOTES = "notes"

    val schema = JournalSchema(
        version = 1,
        fields = listOf(
            FieldDef(
                key = MOOD,
                label = "Mood",
                type = FieldType.MULTI_SELECT,
                options = listOf(
                    FieldOption("happy", "Happy, motivated, productive, valued", 0xFFB5E8C0),
                    FieldOption("avg", "Avg", 0xFFC4DCF8),
                    FieldOption("sad", "Sad, lonely, tired, flat", 0xFFD2D6E0),
                    FieldOption("anxious", "Anxious, angry, frustrated", 0xFFFFC7B5),
                    FieldOption("spinout", "Spinout", 0xFFE6C8F2),
                    FieldOption("sick", "Sick", 0xFFF5E5A0),
                ),
            ),
            FieldDef(key = NAP, label = "Nap", type = FieldType.CHECKBOX),
            FieldDef(key = WORKOUT, label = "Workout", type = FieldType.CHECKBOX),
            FieldDef(key = NOTES, label = "Notes", type = FieldType.TEXT_MULTILINE),
        ),
    )
}
