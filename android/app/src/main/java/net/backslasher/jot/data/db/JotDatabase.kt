package net.backslasher.jot.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Sync-friendly row: UUID primary key, ISO date, JSON values, timestamps and a
 * tombstone flag. Uniqueness of [date] among non-deleted rows is enforced by
 * [net.backslasher.jot.data.RoomJournalRepository] inside a transaction (Room
 * entities can't declare partial unique indexes).
 */
@Entity(
    tableName = "entries",
    indices = [Index("date"), Index("updatedAt")],
)
data class EntryEntity(
    @PrimaryKey val id: String,
    /** ISO-8601 yyyy-MM-dd. */
    val date: String,
    /** JSON object keyed by field key. */
    @ColumnInfo(name = "values") val valuesJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE deleted = 0 ORDER BY date DESC")
    fun observeLive(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun getById(id: String): EntryEntity?

    @Query("SELECT * FROM entries WHERE date = :date AND deleted = 0 LIMIT 1")
    suspend fun getLiveByDate(date: String): EntryEntity?

    @Query("SELECT * FROM entries ORDER BY date DESC, updatedAt DESC")
    suspend fun getAll(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE deleted = 0 ORDER BY date DESC")
    suspend fun getAllLive(): List<EntryEntity>

    @Upsert
    suspend fun upsert(entity: EntryEntity)

    @Query("UPDATE entries SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long)
}

@Database(entities = [EntryEntity::class], version = 1, exportSchema = true)
abstract class JotDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao

    companion object {
        const val NAME = "jot.db"

        fun open(context: Context): JotDatabase =
            Room.databaseBuilder(context.applicationContext, JotDatabase::class.java, NAME).build()
    }
}
