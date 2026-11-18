package net.backslasher.jot.data

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Storage for journal entries. The app talks only to this interface so that a
 * remote/synced implementation can replace the local one later.
 *
 * Invariants:
 *  - at most one non-deleted entry per date;
 *  - deleting sets a tombstone ([Entry.deleted]); the date is then free for a new entry;
 *  - writes are serialized (one writer at a time).
 */
interface JournalRepository {
    /** Non-deleted entries, newest date first. */
    fun observeEntries(): Flow<List<Entry>>

    /** The entry with [id], including a tombstoned one; null if never stored. */
    suspend fun getById(id: String): Entry?

    /** The non-deleted entry for [date], if any. */
    suspend fun getByDate(date: LocalDate): Entry?

    /**
     * Inserts or updates [entry] (matched by id) and returns what was stored.
     * Sets [Entry.updatedAt]; keeps the original createdAt of an existing row.
     *
     * @throws DateConflictException if a different non-deleted entry has the same date.
     */
    suspend fun save(entry: Entry): Entry

    /** Marks the entry as deleted (tombstone). No-op if it doesn't exist. */
    suspend fun delete(id: String)

    /** Every stored entry, optionally including tombstones, newest date first. */
    suspend fun allEntries(includeDeleted: Boolean = false): List<Entry>
}

class DateConflictException(val date: LocalDate, val existingId: String) :
    IllegalStateException("An entry for $date already exists")
