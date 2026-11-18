package net.backslasher.jot.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.backslasher.jot.data.db.EntryEntity
import net.backslasher.jot.data.db.JotDatabase
import java.time.LocalDate

class RoomJournalRepository(
    private val db: JotDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : JournalRepository {

    private val dao = db.entryDao()

    /** Single serialized writer: every mutation goes through this lock. */
    private val writeLock = Mutex()

    override fun observeEntries(): Flow<List<Entry>> =
        dao.observeLive().map { rows -> rows.map { it.toEntry() } }

    override suspend fun getById(id: String): Entry? = dao.getById(id)?.toEntry()

    override suspend fun getByDate(date: LocalDate): Entry? = dao.getLiveByDate(date.toString())?.toEntry()

    override suspend fun save(entry: Entry): Entry = writeLock.withLock {
        db.withTransaction {
            val clash = dao.getLiveByDate(entry.date.toString())
            if (clash != null && clash.id != entry.id) {
                throw DateConflictException(entry.date, clash.id)
            }
            val existing = dao.getById(entry.id)
            val stored = entry.copy(
                createdAt = existing?.createdAt ?: entry.createdAt,
                updatedAt = clock(),
                deleted = false,
            )
            dao.upsert(stored.toEntity())
            stored
        }
    }

    override suspend fun delete(id: String) = writeLock.withLock {
        dao.markDeleted(id, clock())
    }

    override suspend fun allEntries(includeDeleted: Boolean): List<Entry> =
        (if (includeDeleted) dao.getAll() else dao.getAllLive()).map { it.toEntry() }
}

private val json = Json { ignoreUnknownKeys = true }

internal fun EntryEntity.toEntry() = Entry(
    id = id,
    date = LocalDate.parse(date),
    values = parseValues(valuesJson),
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
)

internal fun Entry.toEntity() = EntryEntity(
    id = id,
    date = date.toString(),
    valuesJson = json.encodeToString(JsonObject.serializer(), values),
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted,
)

/** Corrupt or non-object JSON reads as an empty entry rather than crashing the list. */
internal fun parseValues(raw: String): JsonObject =
    runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
