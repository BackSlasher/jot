package net.backslasher.jot.ui.fields

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.backslasher.jot.schema.FieldOption
import net.backslasher.jot.ui.theme.OnPastel

/** A coloured, non-interactive pill for a selected option. */
@Composable
fun OptionChip(option: FieldOption, modifier: Modifier = Modifier, compact: Boolean = false) {
    Text(
        text = option.label,
        color = OnPastel,
        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color(option.color), RoundedCornerShape(50))
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 3.dp else 6.dp),
    )
}

/** Fallback for an option id the schema no longer knows. */
fun unknownOption(id: String) = FieldOption(id = id, label = id, color = 0xFFE0E0E0)
