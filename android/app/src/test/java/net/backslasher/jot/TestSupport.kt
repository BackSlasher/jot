package net.backslasher.jot

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import net.backslasher.jot.data.Entry
import net.backslasher.jot.data.JournalRepository
import net.backslasher.jot.data.RoomJournalRepository
import net.backslasher.jot.data.db.JotDatabase
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

fun inMemoryDb(): JotDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), JotDatabase::class.java).build()

/** Monotonic fake clock: every call returns a later instant. */
class FakeClock(start: Long = 1_700_000_000_000L) : () -> Long {
    private val now = AtomicLong(start)
    override fun invoke(): Long = now.addAndGet(1_000)
}

fun roomRepo(db: JotDatabase, clock: () -> Long = FakeClock()) = RoomJournalRepository(db, clock)

/**
 * Wraps a repository to make saves slow and/or failing, to exercise the
 * "never lose edits" paths.
 */
class ControllableRepository(
    private val delegate: JournalRepository,
) : JournalRepository by delegate {
    @Volatile var saveDelayMillis: Long = 0
    @Volatile var failSaves: Boolean = false
    @Volatile var saveCalls: Int = 0

    override suspend fun save(entry: Entry): Entry {
        saveCalls++
        if (saveDelayMillis > 0) delay(saveDelayMillis)
        if (failSaves) throw IOException("disk on fire (simulated)")
        return delegate.save(entry)
    }
}
