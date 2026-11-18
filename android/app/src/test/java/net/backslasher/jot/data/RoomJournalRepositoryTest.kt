package net.backslasher.jot.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.backslasher.jot.FakeClock
import net.backslasher.jot.data.db.EntryEntity
import net.backslasher.jot.data.db.JotDatabase
import net.backslasher.jot.inMemoryDb
import net.backslasher.jot.roomRepo
import net.backslasher.jot.schema.DefaultSchema
import net.backslasher.jot.schema.DefaultSchema.MOOD
import net.backslasher.jot.schema.DefaultSchema.NAP
import net.backslasher.jot.schema.DefaultSchema.NOTES
import net.backslasher.jot.schema.DefaultSchema.WORKOUT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class RoomJournalRepositoryTest {
    private lateinit var db: JotDatabase
    private lateinit var repo: RoomJournalRepository
    private val day = LocalDate.of(2026, 3, 14)

    @Before fun setUp() {
        db = inMemoryDb()
        repo = roomRepo(db, FakeClock())
    }

    @After fun tearDown() = db.close()

    private fun sample(date: LocalDate = day) = Entry.new(date, now = 1L)
        .let { e ->
            e.copy(
                values = e.values
                    .withOptions(MOOD, listOf("sad", "happy", "sick"))
                    .withBool(NAP, true)
                    .withBool(WORKOUT, false)
                    .withText(NOTES, "Rainy morning, baked bread.\nSecond line."),
            )
        }

    @Test fun roundTripPreservesValuesAndMoodSelectionOrder() = runBlocking {
        val saved = repo.save(sample())
        val loaded = repo.getById(saved.id)!!
        assertEquals(listOf("sad", "happy", "sick"), loaded.options(MOOD))
        assertTrue(loaded.bool(NAP))
        assertFalse(loaded.bool(WORKOUT))
        assertEquals("Rainy morning, baked bread.\nSecond line.", loaded.text(NOTES))
        assertEquals(day, loaded.date)
        assertEquals(saved, loaded)
        assertEquals(listOf(saved.id), repo.observeEntries().first().map { it.id })
    }

    @Test fun updateKeepsCreatedAtAndBumpsUpdatedAt() = runBlocking {
        val first = repo.save(sample())
        val second = repo.save(first.copy(values = first.values.withText(NOTES, "edited"), createdAt = 999))
        assertEquals(first.createdAt, second.createdAt)
        assertTrue(second.updatedAt > first.updatedAt)
        assertEquals("edited", repo.getById(first.id)!!.text(NOTES))
        assertEquals(1, repo.allEntries(includeDeleted = true).size)
    }

    @Test fun onlyOneLiveEntryPerDate() = runBlocking {
        val a = repo.save(sample())
        val b = Entry.new(day, now = 2L).let { it.copy(values = it.values.withText(NOTES, "intruder")) }
        val err = assertThrows(DateConflictException::class.java) { runBlocking { repo.save(b) } }
        assertEquals(a.id, err.existingId)
        assertEquals(a.id, repo.getByDate(day)!!.id)
        assertEquals("Rainy morning, baked bread.\nSecond line.", repo.getByDate(day)!!.text(NOTES))
        assertNull(repo.getById(b.id))
    }

    @Test fun deleteIsTombstoneAndDateCanBeReused() = runBlocking {
        val a = repo.save(sample())
        repo.delete(a.id)

        assertNull(repo.getByDate(day))
        assertTrue(repo.observeEntries().first().isEmpty())
        val tomb = repo.getById(a.id)!!
        assertTrue(tomb.deleted)
        assertTrue(tomb.updatedAt > a.updatedAt)

        val b = repo.save(Entry.new(day, now = 5L).let { it.copy(values = it.values.withBool(NAP, true)) })
        assertNotEquals(a.id, b.id)
        assertEquals(b.id, repo.getByDate(day)!!.id)
        assertEquals(listOf(b.id), repo.allEntries().map { it.id })
        assertEquals(setOf(a.id, b.id), repo.allEntries(includeDeleted = true).map { it.id }.toSet())
    }

    @Test fun listIsNewestFirst() = runBlocking {
        listOf(day.minusDays(2), day, day.minusDays(1)).forEach { repo.save(sample(it)) }
        assertEquals(listOf(day, day.minusDays(1), day.minusDays(2)), repo.observeEntries().first().map { it.date })
    }

    @Test fun unknownAndMissingKeysAreTolerated() = runBlocking {
        // Written by a hypothetical newer client: wrong types, extra keys, missing keys.
        db.entryDao().upsert(
            EntryEntity(
                id = "future-1", date = "2026-01-01",
                valuesJson = """{"mood":["avg","no-such-option",3,"avg"],"nap":"yes","sleepHours":7,"extra":{"a":[1]}}""",
                createdAt = 1, updatedAt = 1, deleted = false,
            ),
        )
        val e = repo.getById("future-1")!!
        assertEquals(listOf("avg", "no-such-option"), e.options(MOOD)) // non-strings dropped, dupes removed
        assertFalse(e.bool(NAP)) // wrong type reads as empty
        assertFalse(e.bool(WORKOUT)) // missing
        assertEquals("", e.text(NOTES)) // missing

        // Editing a known field keeps the unknown keys intact.
        repo.save(e.copy(values = e.values.withText(NOTES, "hello")))
        val raw = db.entryDao().getById("future-1")!!.valuesJson
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertEquals(7, obj["sleepHours"]!!.jsonPrimitive.int)
        assertEquals(1, obj["extra"]!!.jsonObject["a"]!!.jsonArray[0].jsonPrimitive.int)
        assertEquals("hello", obj[NOTES]!!.jsonPrimitive.content)
    }

    @Test fun corruptValuesReadAsEmpty() = runBlocking {
        db.entryDao().upsert(EntryEntity("bad", "2026-02-02", "not json{", 1, 1, false))
        val e = repo.getById("bad")!!
        assertEquals(JsonObject(emptyMap()), e.values)
        assertNotNull(repo.observeEntries().first().single())
    }

    @Test fun exportContainsSchemaAndLiveEntries() = runBlocking {
        val kept = repo.save(sample())
        val gone = repo.save(sample(day.minusDays(1)))
        repo.delete(gone.id)
        val json = Json.parseToJsonElement(
            JournalExport.toJsonString(DefaultSchema.schema, repo.allEntries(), exportedAt = 42),
        ).jsonObject
        assertEquals("jot-export", json["format"]!!.jsonPrimitive.content)
        val fields = json["schema"]!!.jsonObject["fields"]!!.jsonArray
        assertEquals(listOf(MOOD, NAP, WORKOUT, NOTES), fields.map { it.jsonObject["key"]!!.jsonPrimitive.content })
        assertEquals(6, fields[0].jsonObject["options"]!!.jsonArray.size)
        val entries = json["entries"]!!.jsonArray
        assertEquals(listOf(kept.id), entries.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(
            listOf("sad", "happy", "sick"),
            entries[0].jsonObject["values"]!!.jsonObject[MOOD]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
