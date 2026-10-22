package `in`.abhaynayan.app.data

import androidx.room.withTransaction
import `in`.abhaynayan.app.net.Payloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.time.Instant

class ScreeningRepository(private val db: AbhayDatabase, private val requestSync: () -> Unit) {
    val patients: Flow<List<PatientEntity>> = db.patientDao().observeAll()
    val records: Flow<List<ScreeningEntity>> = db.screeningDao().observeAll()
    val syncCounts: Flow<SyncCounts> = db.screeningDao().observeSyncCounts().map { it ?: SyncCounts(0, 0, 0, 0) }

    fun record(id: String): Flow<ScreeningEntity?> = db.screeningDao().observe(id)
    fun referrals(screeningId: String): Flow<List<ReferralEntity>> = db.referralDao().observeForScreening(screeningId)

    suspend fun ensureDemoProfiles() {
        if (db.patientDao().count() > 0) return
        val now = Instant.now().toString()
        db.patientDao().upsert(PatientEntity("ABH-DEMO-MOTHER", "Synthetic demo mother", true, true, now, SubjectKind.MATERNAL))
        db.patientDao().upsert(PatientEntity("ABH-DEMO-NEWBORN", "Synthetic demo newborn", true, true, now, SubjectKind.NEWBORN))
    }

    suspend fun createPatient(nickname: String, synthetic: Boolean, consent: Boolean, kind: String): PatientEntity {
        require(consent) { "Consent is required to store a profile." }
        val existing = db.patientDao().allIds().toSet()
        var id: String
        do { id = newPseudonymousId() } while (id in existing)
        val patient = PatientEntity(id, nickname.trim().take(40), synthetic, consent, Instant.now().toString(), kind)
        db.patientDao().upsert(patient)
        return patient
    }

    suspend fun find(id: String): ScreeningEntity? = db.screeningDao().find(id)
    suspend fun patient(id: String): PatientEntity? = db.patientDao().find(id)

    /** Persists a screening. Only backend-eligible synthetic demo records join the sync queue. */
    suspend fun save(record: ScreeningEntity): ScreeningEntity {
        val patient = db.patientDao().find(record.patientId)
        val stored = when (val e = Payloads.syncEligibility(record, patient)) {
            is Payloads.Eligibility.Eligible -> record.copy(syncState = SyncState.PENDING, lastSyncError = null)
            is Payloads.Eligibility.LocalOnly -> record.copy(syncState = SyncState.LOCAL_ONLY, lastSyncError = e.reason)
        }
        db.screeningDao().insert(stored)
        if (stored.syncState == SyncState.PENDING) requestSync()
        return stored
    }

    suspend fun retryFailed(id: String? = null) {
        if (id == null) db.screeningDao().requeueFailed() else db.screeningDao().requeue(id)
        requestSync()
    }

    fun syncNow() = requestSync()

    suspend fun saveReferral(referral: ReferralEntity) = db.referralDao().upsert(referral)

    suspend fun deleteAllLocalData() = db.withTransaction {
        db.referralDao().deleteAll()
        db.screeningDao().deleteAll()
        db.patientDao().deleteAll()
    }

    companion object {
        private val random = SecureRandom()
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Random pseudonymous ID (no name, date, or sequence information); matches the server ID pattern. */
        fun newPseudonymousId(): String = "ABH-" + (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

        fun newScreeningId(): String = "scr-" + java.util.UUID.randomUUID().toString()
        fun newReferralId(): String = "ref-" + java.util.UUID.randomUUID().toString()
    }
}
