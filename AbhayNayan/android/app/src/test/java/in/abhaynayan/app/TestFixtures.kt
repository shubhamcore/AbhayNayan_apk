package `in`.abhaynayan.app

import `in`.abhaynayan.app.data.PatientEntity
import `in`.abhaynayan.app.data.ScreeningEntity
import `in`.abhaynayan.app.data.SyncState

object TestFixtures {
    val demoPatient = PatientEntity("ABH-DEMO-MOTHER", "Synthetic demo mother", true, true, "2026-10-10T08:00:00Z", "MATERNAL")
    val realPatient = PatientEntity("ABH-K7Q2M9XP", "Asha's neighbour", false, true, "2026-10-10T08:00:00Z", "MATERNAL")

    fun demoRecord(id: String = "scr-1f3a9c2e-0000-4000-8000-000000000001", attempts: Int = 0) = ScreeningEntity(
        screeningId = id, patientId = demoPatient.patientId, screeningType = "maternal_anemia",
        estimate = 9.4, unit = "g/dL", demoTag = "DEMO_MODERATE", confidencePlaceholder = .82, captureQualityProxy = .5,
        modelMode = "DEMO", synthetic = true, confirmationRequired = true, createdAtUtc = "2026-10-10T08:30:00Z",
        resultMode = "DEMO_SYNTHETIC", caseId = "maternal-moderate", qualityStatus = "PASSED",
        metadataJson = """{"pregnancy":"PREGNANT","gestational_week":24,"danger_signs":[]}""",
        syncState = SyncState.PENDING, syncAttempts = attempts,
    )

    fun disabledRecord(id: String = "scr-2b000000-0000-4000-8000-000000000002") = ScreeningEntity(
        screeningId = id, patientId = realPatient.patientId, screeningType = "neonatal_jaundice",
        estimate = null, unit = "mg/dL", demoTag = "NONE", confidencePlaceholder = 0.0, captureQualityProxy = .4,
        modelMode = "NONE", synthetic = false, confirmationRequired = true, createdAtUtc = "2026-10-10T09:00:00Z",
        resultMode = "DISABLED_NO_VALID_MODEL", modelVersion = "none", qualityStatus = "PASSED",
        metadataJson = """{"pregnancy":"UNKNOWN","infant_age_hours":20,"visible_jaundice":true,"danger_signs":[]}""",
        referralUrgency = "URGENT_REFERRAL", syncState = SyncState.LOCAL_ONLY,
    )
}
