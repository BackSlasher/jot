package net.backslasher.jot

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.backslasher.jot.data.JournalRepository
import net.backslasher.jot.data.RoomJournalRepository
import net.backslasher.jot.data.db.JotDatabase
import net.backslasher.jot.schema.DefaultSchema
import net.backslasher.jot.schema.JournalSchema

/** Manual dependency container; swap [repository] for a synced implementation later. */
class AppContainer(app: Application) {
    val schema: JournalSchema = DefaultSchema.schema
    val repository: JournalRepository by lazy { RoomJournalRepository(JotDatabase.open(app)) }

    /**
     * Outlives screens and ViewModels, so a write that has started is never
     * cancelled because the user navigated away or the activity finished.
     */
    val writeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class JotApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
