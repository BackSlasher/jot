package net.backslasher.jot.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import net.backslasher.jot.data.Entry
import net.backslasher.jot.data.JournalExport
import net.backslasher.jot.data.JournalRepository
import net.backslasher.jot.schema.JournalSchema

class ListViewModel(
    private val repository: JournalRepository,
    private val schema: JournalSchema,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** null while the first load is in progress. */
    val entries: StateFlow<List<Entry>?> = repository.observeEntries()
        .map<List<Entry>, List<Entry>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun exportJson(): String =
        JournalExport.toJsonString(schema, repository.allEntries(includeDeleted = false), clock())
}
