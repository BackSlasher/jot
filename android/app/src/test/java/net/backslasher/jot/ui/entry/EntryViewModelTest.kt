package net.backslasher.jot.ui.entry

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import net.backslasher.jot.ControllableRepository
import net.backslasher.jot.FakeClock
import net.backslasher.jot.data.Entry
import net.backslasher.jot.data.RoomJournalRepository
import net.backslasher.jot.data.db.JotDatabase
import net.backslasher.jot.data.asText
import net.backslasher.jot.data.withText
import net.backslasher.jot.inMemoryDb
import net.backslasher.jot.roomRepo
import net.backslasher.jot.schema.DefaultSchema.MOOD
import net.backslasher.jot.schema.DefaultSchema.NAP
import net.backslasher.jot.schema.DefaultSchema.NOTES
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EntryViewModelTest {
    private lateinit var db: JotDatabase
    private lateinit var room: RoomJournalRepository
    private lateinit var repo: ControllableRepository
    private lateinit var writeScope: CoroutineScope
    private val today = LocalDate.of(2026, 5, 20)
    private val vms = mutableListOf<EntryViewModel>()

    @Before fun setUp() {
        // viewModelScope runs on Main; run it unconfined so the (main) test thread can block.
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDb()
        room = roomRepo(db, FakeClock())
        repo = ControllableRepository(room)
        writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun tearDown() {
        writeScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newVm(
        id: String? = null,
        date: LocalDate? = today,
        debounce: Long = 10_000, // long: an edit is definitely still pending when we close
    ) = EntryViewModel(
        repository = repo, savedState = SavedStateHandle(), writeScope = writeScope,
        requestedId = id, requestedDate = date, today = { today }, clock = FakeClock(),
        textDebounceMillis = debounce,
    ).also { vms += it }

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T =
        withTimeout(5_000) { first(predicate) }

    private suspend fun EntryViewModel.loaded() = state.await { !it.loading }

    /** Records nav events; [onEvent] runs at the moment each event is delivered. */
    private fun CoroutineScope.recordEvents(
        vm: EntryViewModel,
        onEvent: suspend (EntryNavEvent) -> Unit = {},
    ): MutableList<EntryNavEvent> {
        val events = Collections.synchronizedList(mutableListOf<EntryNavEvent>())
        launch(Dispatchers.Unconfined) { vm.events.collect { onEvent(it); events += it } }
        return events
    }

    @Test fun typingThenImmediateCloseCommitsFullTextBeforeCloseFires() = runBlocking {
        repo.saveDelayMillis = 300 // a slow disk: close must wait for it
        val vm = newVm()
        vm.loaded()

        val full = "Walked to the market, bought figs. Long nap after lunch."
        for (i in 1..full.length) vm.setText(NOTES, full.substring(0, i)) // keystroke by keystroke

        // The debounced write hasn't happened yet: without the flush the text would be lost.
        assertNull(room.getByDate(today))

        val textInDbWhenClosed = CompletableDeferred<String?>()
        val job = launch(Dispatchers.Unconfined) {
            vm.events.collect { event ->
                if (event == EntryNavEvent.Close) textInDbWhenClosed.complete(room.getByDate(today)?.text(NOTES))
            }
        }
        vm.requestClose()

        assertEquals(full, withTimeout(5_000) { textInDbWhenClosed.await() })
        job.cancel()
    }

    @Test fun failingWriteBlocksCloseUntilRetrySucceeds() = runBlocking {
        val vm = newVm()
        vm.loaded()
        val events = recordEvents(vm)

        repo.failSaves = true
        vm.setText(NOTES, "Note that must not vanish")
        vm.requestClose()

        val blocked = vm.state.await { it.closeBlocked }
        assertNotNull(blocked.writeError)
        delay(300)
        assertTrue("close must not fire after a failed write", events.isEmpty())
        assertNull(room.getByDate(today))
        // The edit is still in memory.
        assertEquals("Note that must not vanish", vm.state.value.values[NOTES].asText())

        repo.failSaves = false
        vm.retryWrite()
        withTimeout(5_000) { while (events.isEmpty()) delay(10) }
        assertEquals(listOf<EntryNavEvent>(EntryNavEvent.Close), events.toList())
        assertEquals("Note that must not vanish", room.getByDate(today)!!.text(NOTES))
        coroutineContext.cancelChildren()
    }

    @Test fun discardAfterFailureClosesWithoutSaving() = runBlocking {
        val vm = newVm()
        vm.loaded()
        val events = recordEvents(vm)
        repo.failSaves = true
        vm.setChecked(NAP, true)
        vm.requestClose()
        vm.state.await { it.closeBlocked }

        repo.failSaves = false
        vm.discardAndClose()
        withTimeout(5_000) { while (events.isEmpty()) delay(10) }
        assertEquals(EntryNavEvent.Close, events.single())
        vm.flush()
        assertNull(room.getByDate(today))
        coroutineContext.cancelChildren()
    }

    @Test fun untouchedNewEntryIsNotStored() = runBlocking {
        val vm = newVm()
        vm.loaded()
        val events = recordEvents(vm)
        vm.changeDate(today.minusDays(3)) // date alone doesn't count as content
        vm.state.await { it.date == today.minusDays(3) }
        vm.requestClose()
        withTimeout(5_000) { while (events.isEmpty()) delay(10) }
        assertEquals(0, repo.saveCalls)
        assertTrue(room.allEntries(includeDeleted = true).isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test fun openingNewForExistingDateOpensThatEntry() = runBlocking {
        val existing = room.save(Entry.new(today, 1).let { it.copy(values = it.values.withText(NOTES, "already here")) })
        val vm = newVm(date = today)
        val s = vm.loaded()
        assertEquals(existing.id, s.entryId)
        assertTrue(s.persisted)

        vm.setText(NOTES, "already here, and more")
        assertTrue(vm.flush())
        val all = room.allEntries(includeDeleted = true)
        assertEquals(1, all.size)
        assertEquals("already here, and more", all.single().text(NOTES))
    }

    @Test fun changingToTakenDateOffersExistingEntryAndDoesNotClobber() = runBlocking {
        val other = room.save(Entry.new(today.minusDays(1), 1).let { it.copy(values = it.values.withText(NOTES, "yesterday")) })
        val vm = newVm(date = today)
        vm.loaded()
        val events = recordEvents(vm)
        vm.setText(NOTES, "today")

        vm.changeDate(today.minusDays(1))
        val conflict = vm.state.await { it.dateConflict != null }.dateConflict!!
        assertEquals(other.id, conflict.existingId)
        assertEquals(today, vm.state.value.date)

        vm.openConflictingEntry()
        withTimeout(5_000) { while (events.isEmpty()) delay(10) }
        assertEquals(EntryNavEvent.OpenEntry(other.id), events.single())
        // Current entry was saved before leaving; the other one is untouched.
        assertEquals("today", room.getByDate(today)!!.text(NOTES))
        assertEquals("yesterday", room.getByDate(today.minusDays(1))!!.text(NOTES))
        coroutineContext.cancelChildren()
    }

    @Test fun changingToFreeDateMovesEntry() = runBlocking {
        val vm = newVm(date = today)
        vm.loaded()
        vm.setChecked(NAP, true)
        assertTrue(vm.flush())
        vm.changeDate(today.minusDays(7))
        vm.state.await { it.date == today.minusDays(7) }
        assertTrue(vm.flush())
        assertNull(room.getByDate(today))
        assertTrue(room.getByDate(today.minusDays(7))!!.bool(NAP))
    }

    @Test fun moodKeepsSelectionOrder() = runBlocking {
        val vm = newVm()
        vm.loaded()
        listOf("sad", "happy", "avg").forEach { vm.toggleOption(MOOD, it) }
        vm.toggleOption(MOOD, "happy") // deselect
        vm.toggleOption(MOOD, "sick")
        assertTrue(vm.flush())
        assertEquals(listOf("sad", "avg", "sick"), room.getByDate(today)!!.options(MOOD))
    }

    @Test fun backgroundFlushPersistsPendingText() = runBlocking {
        val vm = newVm()
        vm.loaded()
        vm.setText(NOTES, "typed, then pressed home")
        vm.flushInBackground() // what ON_PAUSE / ON_STOP do
        withTimeout(5_000) { while (room.getByDate(today)?.text(NOTES) != "typed, then pressed home") delay(10) }
    }

    @Test fun deleteTombstonesAndCloses() = runBlocking {
        val vm = newVm()
        vm.loaded()
        val events = recordEvents(vm)
        vm.setChecked(NAP, true)
        assertTrue(vm.flush())
        val id = vm.state.value.entryId
        vm.delete()
        withTimeout(5_000) { while (events.isEmpty()) delay(10) }
        assertEquals(EntryNavEvent.Close, events.single())
        assertTrue(room.getById(id)!!.deleted)
        assertNull(room.getByDate(today))
        coroutineContext.cancelChildren()
    }

    @Test fun openingDeletedEntryByIdShowsError() = runBlocking {
        val e = room.save(Entry.new(today, 1).let { it.copy(values = it.values.withText(NOTES, "x")) })
        room.delete(e.id)
        val vm = newVm(id = e.id, date = null)
        val s = vm.loaded()
        assertNotNull(s.loadError)
        vm.setText(NOTES, "ignored")
        assertFalse(vm.state.value.hasUnsavedChanges)
    }
}
