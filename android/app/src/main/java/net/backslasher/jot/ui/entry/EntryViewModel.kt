package net.backslasher.jot.ui.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import net.backslasher.jot.data.DateConflictException
import net.backslasher.jot.data.Entry
import net.backslasher.jot.data.JournalRepository
import net.backslasher.jot.data.asOptionIds
import net.backslasher.jot.data.withBool
import net.backslasher.jot.data.withOptions
import net.backslasher.jot.data.withText
import java.time.LocalDate
import java.util.UUID

data class DateConflict(val date: LocalDate, val existingId: String)

data class EntryUiState(
    val loading: Boolean = true,
    val entryId: String = "",
    val date: LocalDate = LocalDate.MIN,
    val values: JsonObject = JsonObject(emptyMap()),
    /** Incremented on every user change. */
    val editVersion: Long = 0,
    /** The [editVersion] that is known to be committed to the database. */
    val savedVersion: Long = 0,
    /** The entry exists in the database. */
    val persisted: Boolean = false,
    /** A close has been requested and is waiting for the pending write. */
    val closing: Boolean = false,
    /** The last write failed; edits are still in memory. */
    val writeError: String? = null,
    /** Leaving was blocked by a failed write: ask the user to retry or discard. */
    val closeBlocked: Boolean = false,
    val dateConflict: DateConflict? = null,
    val loadError: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = editVersion != savedVersion
}

sealed interface EntryNavEvent {
    data object Close : EntryNavEvent
    data class OpenEntry(val id: String) : EntryNavEvent
}

/**
 * Editor for one day's entry. Every change is persisted automatically:
 *
 *  - The in-memory [state] is the source of truth for the form and is updated
 *    synchronously on every edit.
 *  - Writes always persist the *latest* snapshot, one at a time ([persistLock];
 *    the repository serializes across editors too), and run in [writeScope], which
 *    outlives this ViewModel so a started write is never cancelled by navigation.
 *  - Text edits are debounced by [textDebounceMillis]; everything else is written
 *    immediately. [flush] cancels any pending debounce and writes now.
 *  - Leaving ([requestClose]) only emits [EntryNavEvent.Close] after [flush]
 *    succeeded. A failed write blocks leaving until the user retries or discards.
 *  - A brand-new entry isn't stored until the user changes something.
 */
class EntryViewModel(
    private val repository: JournalRepository,
    private val savedState: SavedStateHandle,
    private val writeScope: CoroutineScope,
    private val requestedId: String?,
    private val requestedDate: LocalDate?,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val textDebounceMillis: Long = DEFAULT_TEXT_DEBOUNCE_MILLIS,
) : ViewModel() {

    private val _state = MutableStateFlow(EntryUiState())
    val state: StateFlow<EntryUiState> = _state.asStateFlow()

    private val _events = Channel<EntryNavEvent>(Channel.BUFFERED)
    val events: Flow<EntryNavEvent> = _events.receiveAsFlow()

    private val persistLock = Mutex()

    @Volatile private var pendingWrite: Job? = null
    @Volatile private var discarded = false
    @Volatile private var createdAt = 0L
    private var closeJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    // ---- loading -------------------------------------------------------------------

    private suspend fun load() {
        try {
            val savedId = savedState.get<String>(KEY_ID)
            val existing = savedId?.let { repository.getById(it) }?.takeUnless { it.deleted }
                ?: requestedId?.let { repository.getById(it) }?.takeUnless { it.deleted }
                ?: requestedDate?.let { repository.getByDate(it) }
            if (existing != null) {
                createdAt = existing.createdAt
                savedState[KEY_ID] = existing.id
                _state.update {
                    it.copy(
                        loading = false, entryId = existing.id, date = existing.date,
                        values = existing.values, persisted = true,
                    )
                }
            } else if (requestedId != null && requestedDate == null && savedId == null) {
                // Asked for a specific entry that no longer exists (e.g. deleted).
                _state.update { it.copy(loading = false, loadError = "This entry no longer exists.") }
            } else {
                val id = savedId ?: UUID.randomUUID().toString()
                val date = savedState.get<String>(KEY_DATE)?.let(LocalDate::parse) ?: requestedDate ?: today()
                savedState[KEY_ID] = id
                createdAt = clock()
                _state.update { it.copy(loading = false, entryId = id, date = date) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, loadError = e.message ?: e.toString()) }
        }
    }

    // ---- edits ---------------------------------------------------------------------

    fun setChecked(key: String, checked: Boolean) = edit(debounce = false) { it.withBool(key, checked) }

    fun setText(key: String, text: String) = edit(debounce = true) { it.withText(key, text) }

    /** Adds [optionId] at the end of the selection, or removes it if already selected. */
    fun toggleOption(key: String, optionId: String) = edit(debounce = false) { values ->
        val current = values[key].asOptionIds()
        values.withOptions(key, if (optionId in current) current - optionId else current + optionId)
    }

    private fun edit(debounce: Boolean, transform: (JsonObject) -> JsonObject) {
        val s = _state.value
        if (s.loading || s.loadError != null || discarded) return
        _state.update { it.copy(values = transform(it.values), editVersion = it.editVersion + 1) }
        scheduleWrite(debounce)
    }

    fun changeDate(newDate: LocalDate) {
        val s = _state.value
        if (s.loading || s.loadError != null || discarded || newDate == s.date) return
        viewModelScope.launch {
            val clash = try {
                repository.getByDate(newDate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(writeError = e.message ?: e.toString()) }
                return@launch
            }
            if (clash != null && clash.id != _state.value.entryId) {
                _state.update { it.copy(dateConflict = DateConflict(newDate, clash.id)) }
                return@launch
            }
            savedState[KEY_DATE] = newDate.toString()
            val shouldStore = _state.value.persisted || _state.value.hasUnsavedChanges
            _state.update {
                it.copy(date = newDate, editVersion = if (shouldStore) it.editVersion + 1 else it.editVersion)
            }
            // An untouched new entry stays unsaved: the date alone is not worth a row.
            if (shouldStore) scheduleWrite(debounce = false)
        }
    }

    fun dismissDateConflict() = _state.update { it.copy(dateConflict = null) }

    /** Saves this entry, then asks to navigate to the entry that already has the chosen date. */
    fun openConflictingEntry() {
        val conflict = _state.value.dateConflict ?: return
        _state.update { it.copy(dateConflict = null) }
        leave(EntryNavEvent.OpenEntry(conflict.existingId))
    }

    // ---- writing -------------------------------------------------------------------

    private fun scheduleWrite(debounce: Boolean) {
        pendingWrite?.cancel()
        pendingWrite = writeScope.launch {
            if (debounce) delay(textDebounceMillis)
            // Only the debounce is cancellable; once writing, finish.
            withContext(NonCancellable) { writeLatest() }
        }
    }

    /** Persists the latest state if it has unsaved changes. Returns false if the write failed. */
    private suspend fun writeLatest(): Boolean {
        try {
            persistLock.withLock {
                val snap = _state.value
                if (discarded || snap.loading || !snap.hasUnsavedChanges) return true
                repository.save(
                    Entry(
                        id = snap.entryId, date = snap.date, values = snap.values,
                        createdAt = createdAt, updatedAt = clock(),
                    ),
                )
                _state.update {
                    it.copy(
                        savedVersion = maxOf(it.savedVersion, snap.editVersion),
                        persisted = true,
                        writeError = null,
                    )
                }
            }
            return true
        } catch (e: DateConflictException) {
            _state.update { it.copy(writeError = "Another entry already exists for ${e.date}.") }
            return false
        } catch (e: Exception) {
            _state.update { it.copy(writeError = e.message ?: e.toString()) }
            return false
        }
    }

    /**
     * Cancels any pending debounce and writes the latest state now; suspends until it
     * is committed. The write itself runs in [writeScope] and survives cancellation
     * of the caller.
     */
    suspend fun flush(): Boolean {
        pendingWrite?.cancel()
        return writeScope.async { writeLatest() }.await()
    }

    /** Fire-and-forget flush for lifecycle ON_PAUSE / ON_STOP. */
    fun flushInBackground() {
        pendingWrite?.cancel()
        writeScope.launch { writeLatest() }
    }

    fun retryWrite() {
        if (_state.value.closeBlocked) {
            _state.update { it.copy(closeBlocked = false) }
            requestClose()
        } else {
            writeScope.launch { writeLatest() }
        }
    }

    // ---- leaving -------------------------------------------------------------------

    /** Back / X: navigate away only once everything is committed. */
    fun requestClose() = leave(EntryNavEvent.Close)

    private fun leave(event: EntryNavEvent) {
        if (closeJob?.isActive == true) return
        closeJob = viewModelScope.launch {
            _state.update { it.copy(closing = true) }
            // Loop: a keystroke can land while the previous flush was writing.
            while (true) {
                if (!flush()) {
                    _state.update { it.copy(closing = false, closeBlocked = true) }
                    return@launch
                }
                if (discarded || !_state.value.hasUnsavedChanges) break
            }
            _events.send(event)
        }
    }

    /** Drops unsaved changes (the last committed version stays) and leaves. */
    fun discardAndClose() {
        discarded = true
        pendingWrite?.cancel()
        _state.update { it.copy(closeBlocked = false, writeError = null) }
        viewModelScope.launch { _events.send(EntryNavEvent.Close) }
    }

    /** Tombstones the entry (if it was ever stored) and leaves. */
    fun delete() {
        viewModelScope.launch {
            discarded = true
            pendingWrite?.cancel()
            val ok = writeScope.async {
                try {
                    persistLock.withLock { if (_state.value.persisted) repository.delete(_state.value.entryId) }
                    true
                } catch (e: Exception) {
                    _state.update { it.copy(writeError = "Delete failed: ${e.message ?: e}") }
                    false
                }
            }.await()
            if (ok) _events.send(EntryNavEvent.Close) else discarded = false
        }
    }

    override fun onCleared() {
        // Normally a no-op (leaving flushed already); covers the activity being finished.
        if (!discarded) writeScope.launch { writeLatest() }
    }

    companion object {
        const val DEFAULT_TEXT_DEBOUNCE_MILLIS = 300L
        private const val KEY_ID = "entryId"
        private const val KEY_DATE = "draftDate"
    }
}
