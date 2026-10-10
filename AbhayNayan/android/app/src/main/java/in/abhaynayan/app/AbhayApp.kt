package `in`.abhaynayan.app

import android.app.Application
import androidx.room.Room
import `in`.abhaynayan.app.data.AbhayDatabase
import `in`.abhaynayan.app.data.ScreeningRepository
import `in`.abhaynayan.app.net.ApiClient
import `in`.abhaynayan.app.net.NetworkMonitor
import `in`.abhaynayan.app.net.SettingsStore
import `in`.abhaynayan.app.services.CounsellingService
import `in`.abhaynayan.app.sync.RoomSyncStore
import `in`.abhaynayan.app.sync.SyncEngine
import `in`.abhaynayan.app.sync.SyncWorker

/** Manual dependency container; the app is small enough not to need a DI framework. */
class AbhayApp : Application() {
    val database: AbhayDatabase by lazy {
        Room.databaseBuilder(this, AbhayDatabase::class.java, "abhaynayan-local.db").build()
    }
    val settings: SettingsStore by lazy { SettingsStore(this, BuildConfig.DEFAULT_BACKEND_URL) }
    val api: ApiClient by lazy { ApiClient(baseUrl = { settings.activeBaseUrl() }) }
    val network: NetworkMonitor by lazy { NetworkMonitor(this) }
    val syncEngine: SyncEngine by lazy { SyncEngine(RoomSyncStore(database), api) }
    val repository: ScreeningRepository by lazy { ScreeningRepository(database) { SyncWorker.enqueue(this) } }
    val counselling: CounsellingService by lazy { CounsellingService(api) }

    override fun onCreate() {
        super.onCreate()
        // Pick up records left PENDING by a previous session once connectivity allows.
        SyncWorker.enqueue(this)
    }
}
