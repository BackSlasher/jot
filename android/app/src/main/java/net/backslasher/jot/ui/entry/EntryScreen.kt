package net.backslasher.jot.ui.entry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonObject
import net.backslasher.jot.data.asBoolean
import net.backslasher.jot.data.asOptionIds
import net.backslasher.jot.data.asText
import net.backslasher.jot.schema.FieldDef
import net.backslasher.jot.schema.FieldType
import net.backslasher.jot.schema.JournalSchema
import net.backslasher.jot.ui.fields.OptionChip
import net.backslasher.jot.ui.fields.unknownOption
import net.backslasher.jot.ui.formatEntryDate
import java.time.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(
    viewModel: EntryViewModel,
    schema: JournalSchema,
    onClose: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Every way of leaving (system back, predictive back gesture, the X button) goes
    // through requestClose(), which navigates only after the pending write commits.
    // This handler is registered after NavHost's own, so it takes precedence and the
    // gesture can never pop the destination by itself.
    BackHandler(enabled = true) { viewModel.requestClose() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EntryNavEvent.Close -> onClose()
                is EntryNavEvent.OpenEntry -> onOpenEntry(event.id)
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.flushInBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val status = when {
                        state.closing -> "Saving…"
                        state.writeError != null -> "Not saved"
                        state.hasUnsavedChanges -> "Saving…"
                        state.persisted -> "Saved"
                        else -> ""
                    }
                    Text(status, style = MaterialTheme.typography.labelLarge)
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.requestClose() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !state.loading) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.loadError != null -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(state.loadError!!, style = MaterialTheme.typography.bodyLarge)
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                state.writeError?.takeUnless { state.closeBlocked }?.let { error ->
                    WriteErrorBanner(error, onRetry = viewModel::retryWrite)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatEntryDate(state.date),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { pickDate = true }) {
                        Icon(Icons.Filled.DateRange, contentDescription = "Change date")
                    }
                }
                schema.fields.forEach { field ->
                    FieldEditor(field, state.entryId, state.values, viewModel)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (pickDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.date.toEpochDay() * MILLIS_PER_DAY)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickDate = false
                    pickerState.selectedDateMillis?.let {
                        viewModel.changeDate(LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY)))
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    state.dateConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDateConflict,
            title = { Text("Entry already exists") },
            text = { Text("There is already an entry for ${formatEntryDate(conflict.date)}. Open it instead?") },
            confirmButton = { TextButton(onClick = viewModel::openConflictingEntry) { Text("Open it") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDateConflict) { Text("Keep editing") } },
        )
    }

    if (state.closeBlocked) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Couldn't save") },
            text = {
                Text("Your latest changes couldn't be saved:\n${state.writeError ?: "unknown error"}")
            },
            confirmButton = { TextButton(onClick = viewModel::retryWrite) { Text("Retry") } },
            dismissButton = { TextButton(onClick = viewModel::discardAndClose) { Text("Discard changes") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete entry?") },
            text = { Text("The entry for ${formatEntryDate(state.date)} will be deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WriteErrorBanner(error: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Couldn't save: $error", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun FieldEditor(field: FieldDef, entryId: String, values: JsonObject, viewModel: EntryViewModel) {
    when (field.type) {
        FieldType.CHECKBOX -> {
            val checked = values[field.key].asBoolean()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, role = Role.Checkbox) { viewModel.setChecked(field.key, it) },
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.size(8.dp))
                Text(field.label, style = MaterialTheme.typography.titleMedium)
            }
        }

        FieldType.TEXT_MULTILINE -> {
            // The field keeps its own TextFieldValue (cursor/selection) and pushes every
            // change synchronously to the ViewModel; re-seeded when a different entry loads.
            var text by remember(entryId, field.key) {
                val initial = values[field.key].asText()
                mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    val changed = it.text != text.text
                    text = it
                    if (changed) viewModel.setText(field.key, it.text)
                },
                label = { Text(field.label) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FieldType.MULTI_SELECT -> MultiSelectEditor(field, values[field.key].asOptionIds()) {
            viewModel.toggleOption(field.key, it)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MultiSelectEditor(field: FieldDef, selected: List<String>, onToggle: (String) -> Unit) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(field.label, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // Selection order, not schema order.
            selected.forEach { id ->
                OptionChip(
                    field.option(id) ?: unknownOption(id),
                    modifier = Modifier.clickable { sheetOpen = true },
                )
            }
            FilledTonalIconButton(onClick = { sheetOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Choose ${field.label.lowercase()}")
            }
        }
    }
    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    field.label,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                field.options.forEach { option ->
                    val isSelected = option.id in selected
                    ListItem(
                        headlineContent = { Text(option.label) },
                        leadingContent = {
                            Box(Modifier.size(20.dp).background(Color(option.color), CircleShape))
                        },
                        trailingContent = { Checkbox(checked = isSelected, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.toggleable(value = isSelected, role = Role.Checkbox) { onToggle(option.id) },
                    )
                }
            }
        }
    }
}
