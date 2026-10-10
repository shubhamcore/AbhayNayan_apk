package `in`.abhaynayan.app.data

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "patients")
data class PatientEntity(
    @PrimaryKey val patientId: String,
    /** Optional local nickname. Never synced, never exported to the server. */
    val displayName: String,
    val isSyntheticDemo: Boolean,
    val consentRecorded: Boolean,
    val createdAtUtc: String,
    /** MATERNAL, NEWBORN, or UNSPECIFIED (profiles created before v2). */
    @ColumnInfo(defaultValue = "UNSPECIFIED") val subjectKind: String = SubjectKind.UNSPECIFIED,
)

object SubjectKind {
    const val MATERNAL = "MATERNAL"
    const val NEWBORN = "NEWBORN"
    const val UNSPECIFIED = "UNSPECIFIED"
}

/** Lifecycle of a record with respect to the backend. */
object SyncState {
    /** Not eligible for the current backend contract (non-demo or no estimate). Kept on the device. */
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val PENDING = "PENDING"
    const val SYNCING = "SYNCING"
    const val SYNCED = "SYNCED"
    /** Rejected by the server or retried too often; needs a manual retry. */
    const val FAILED = "FAILED"
    /** Contract 1.0: the server holds a different record with this ID (first write wins). Local copy kept. */
    const val CONFLICT = "CONFLICT"
    /** Contract 1.0: the server will never accept this record. Local copy kept. */
    const val REJECTED = "REJECTED"
}

@Entity(tableName = "screenings", indices = [Index("patientId"), Index("createdAtUtc"), Index("syncState")])
data class ScreeningEntity(
    @PrimaryKey val screeningId: String,
    val patientId: String,
    val screeningType: String,
    /** Null whenever no supported model produced an output. */
    val estimate: Double?,
    val unit: String,
    val demoTag: String,
    val confidencePlaceholder: Double,
    val captureQualityProxy: Double,
    val modelMode: String = "DEMO",
    val synthetic: Boolean = true,
    val confirmationRequired: Boolean = true,
    val createdAtUtc: String,
    @ColumnInfo(defaultValue = "DEMO_SYNTHETIC") val resultMode: String = "DEMO_SYNTHETIC",
    val caseId: String? = null,
    @ColumnInfo(defaultValue = "fixed-demo-fixtures-v1") val modelVersion: String = "fixed-demo-fixtures-v1",
    @ColumnInfo(defaultValue = "NOT_RECORDED") val qualityStatus: String = "NOT_RECORDED",
    @ColumnInfo(defaultValue = "") val captureSource: String = "",
    @ColumnInfo(defaultValue = "NOT_RECORDED") val differentialStatus: String = "NOT_RECORDED",
    @ColumnInfo(defaultValue = "{}") val metadataJson: String = "{}",
    @ColumnInfo(defaultValue = "CONFIRMATORY_TESTING") val referralUrgency: String = "CONFIRMATORY_TESTING",
    @ColumnInfo(defaultValue = "PENDING") val syncState: String = SyncState.PENDING,
    @ColumnInfo(defaultValue = "0") val syncAttempts: Int = 0,
    val lastSyncError: String? = null,
    val lastSyncAttemptAtUtc: String? = null,
    val serverConfirmedAtUtc: String? = null,
    @ColumnInfo(defaultValue = "demo-worker") val workerId: String = "demo-worker",
    @ColumnInfo(defaultValue = "Demo PHC") val phc: String = "Demo PHC",
)

@Entity(tableName = "referrals", indices = [Index("screeningId")])
data class ReferralEntity(
    @PrimaryKey val referralId: String,
    val screeningId: String,
    val createdAtUtc: String,
    val language: String,
    val urgency: String,
    val nextStep: String,
    /** LOCAL_ONLY, PENDING, CREATED, FAILED. */
    val serverState: String,
    val pdfPath: String? = null,
    val lastError: String? = null,
)

data class SyncCounts(val pending: Int, val failed: Int, val synced: Int, val localOnly: Int)

@Dao
interface PatientDao {
    @Query("SELECT * FROM patients ORDER BY createdAtUtc DESC")
    fun observeAll(): Flow<List<PatientEntity>>

    @Query("SELECT COUNT(*) FROM patients")
    suspend fun count(): Int

    @Query("SELECT * FROM patients WHERE patientId = :id LIMIT 1")
    suspend fun find(id: String): PatientEntity?

    @Query("SELECT patientId FROM patients")
    suspend fun allIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(patient: PatientEntity)

    @Query("DELETE FROM patients")
    suspend fun deleteAll()
}

@Dao
interface ScreeningDao {
    @Query("SELECT * FROM screenings ORDER BY createdAtUtc DESC")
    fun observeAll(): Flow<List<ScreeningEntity>>

    @Query("SELECT * FROM screenings WHERE screeningId = :id LIMIT 1")
    fun observe(id: String): Flow<ScreeningEntity?>

    @Query("SELECT * FROM screenings WHERE screeningId = :id LIMIT 1")
    suspend fun find(id: String): ScreeningEntity?

    @Query("SELECT COUNT(*) FROM screenings")
    suspend fun count(): Int

    @Query(
        "SELECT " +
            "SUM(CASE WHEN syncState IN ('PENDING','SYNCING') THEN 1 ELSE 0 END) AS pending, " +
            "SUM(CASE WHEN syncState IN ('FAILED','CONFLICT','REJECTED') THEN 1 ELSE 0 END) AS failed, " +
            "SUM(CASE WHEN syncState = 'SYNCED' THEN 1 ELSE 0 END) AS synced, " +
            "SUM(CASE WHEN syncState = 'LOCAL_ONLY' THEN 1 ELSE 0 END) AS localOnly " +
            "FROM screenings",
    )
    fun observeSyncCounts(): Flow<SyncCounts?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(screening: ScreeningEntity): Long

    @Query("SELECT * FROM screenings WHERE syncState = 'PENDING' ORDER BY createdAtUtc ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<ScreeningEntity>

    /** Claims rows atomically so two sync runs cannot send the same row concurrently. */
    @Query("UPDATE screenings SET syncState = 'SYNCING', lastSyncAttemptAtUtc = :now WHERE screeningId IN (:ids) AND syncState = 'PENDING'")
    suspend fun markSyncing(ids: List<String>, now: String): Int

    @Query("UPDATE screenings SET syncState = 'SYNCED', serverConfirmedAtUtc = :now, lastSyncError = NULL WHERE screeningId IN (:ids)")
    suspend fun markSynced(ids: List<String>, now: String)

    @Query("UPDATE screenings SET syncState = :state, syncAttempts = syncAttempts + 1, lastSyncError = :error WHERE screeningId IN (:ids)")
    suspend fun markAttemptFailed(ids: List<String>, state: String, error: String)

    @Query("UPDATE screenings SET syncState = 'PENDING' WHERE syncState = 'SYNCING'")
    suspend fun resetInterruptedSync(): Int

    @Query("SELECT screeningId FROM screenings WHERE screeningId IN (:ids) AND syncState = 'SYNCING'")
    suspend fun syncingAmong(ids: List<String>): List<String>

    @Query("UPDATE screenings SET syncState = 'PENDING' WHERE screeningId IN (:ids) AND syncState = 'SYNCING'")
    suspend fun releaseClaim(ids: List<String>)

    @Query("UPDATE screenings SET syncState = :state, lastSyncError = :note WHERE screeningId IN (:ids)")
    suspend fun setState(ids: List<String>, state: String, note: String?)

    @Query("UPDATE screenings SET syncState = 'PENDING', syncAttempts = 0, lastSyncError = NULL WHERE screeningId = :id AND syncState = 'FAILED'")
    suspend fun requeue(id: String): Int

    @Query("UPDATE screenings SET syncState = 'PENDING', syncAttempts = 0, lastSyncError = NULL WHERE syncState = 'FAILED'")
    suspend fun requeueFailed(): Int

    @Query("SELECT syncAttempts FROM screenings WHERE screeningId = :id")
    suspend fun attempts(id: String): Int?

    @Query("DELETE FROM screenings")
    suspend fun deleteAll()
}

@Dao
interface ReferralDao {
    @Query("SELECT * FROM referrals WHERE screeningId = :screeningId ORDER BY createdAtUtc DESC")
    fun observeForScreening(screeningId: String): Flow<List<ReferralEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(referral: ReferralEntity)

    @Query("DELETE FROM referrals")
    suspend fun deleteAll()
}

@Database(
    entities = [PatientEntity::class, ScreeningEntity::class, ReferralEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AbhayDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun screeningDao(): ScreeningDao
    abstract fun referralDao(): ReferralDao
}
