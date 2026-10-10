package `in`.abhaynayan.app.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import `in`.abhaynayan.app.AbhayApp
import `in`.abhaynayan.app.data.AbhayDatabase
import `in`.abhaynayan.app.data.PatientEntity
import `in`.abhaynayan.app.data.ScreeningEntity
import java.util.concurrent.TimeUnit

class RoomSyncStore(private val db: AbhayDatabase) : SyncStore {
    private val dao = db.screeningDao()
    override suspend fun resetInterrupted() { dao.resetInterruptedSync() }
    override suspend fun pending(limit: Int): List<ScreeningEntity> = dao.pending(limit)
    override suspend fun patient(id: String): PatientEntity? = db.patientDao().find(id)
    override suspend fun claim(ids: List<String>, now: String): List<String> = db.withTransaction {
        dao.markSyncing(ids, now)
        dao.syncingAmong(ids)
    }
    override suspend fun release(ids: List<String>) = dao.releaseClaim(ids)
    override suspend fun markSynced(ids: List<String>, now: String) { if (ids.isNotEmpty()) dao.markSynced(ids, now) }
    override suspend fun markAttemptFailed(ids: List<String>, state: String, error: String) = dao.markAttemptFailed(ids, state, error)
    override suspend fun setState(ids: List<String>, state: String, note: String?) = dao.setState(ids, state, note)
}

/** Deferred sync. Runs only with network; WorkManager applies exponential backoff on retry. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AbhayApp
        if (app.settings.activeBaseUrl() == null) return Result.success()
        val outcome = app.syncEngine.run()
        return if (outcome.retryLater && runAttemptCount < 10) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "abhaynayan-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // KEEP: a queued/running sync is not duplicated by repeated taps or saves.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
