package `in`.abhaynayan.app.ui

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.abhaynayan.app.AbhayApp
import `in`.abhaynayan.app.BuildConfig
import `in`.abhaynayan.app.core.AnalysisOutcome
import `in`.abhaynayan.app.core.AssetSource
import `in`.abhaynayan.app.core.Biomarker
import `in`.abhaynayan.app.core.CaptureAssessment
import `in`.abhaynayan.app.core.CapturedFrames
import `in`.abhaynayan.app.core.EdgeModelLoader
import `in`.abhaynayan.app.core.FrameMetadata
import `in`.abhaynayan.app.core.JaundiceClassScore
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.core.MetadataForm
import `in`.abhaynayan.app.core.MetadataValidator
import `in`.abhaynayan.app.core.ModelRegistry
import `in`.abhaynayan.app.core.ModelStatus
import `in`.abhaynayan.app.core.RgbImage
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.core.UnavailableBilirubinModel
import `in`.abhaynayan.app.core.WorkflowEngine
import `in`.abhaynayan.app.core.WorkflowStage
import `in`.abhaynayan.app.data.PatientEntity
import `in`.abhaynayan.app.data.ReferralEntity
import `in`.abhaynayan.app.data.ScreeningEntity
import `in`.abhaynayan.app.data.ScreeningRepository
import `in`.abhaynayan.app.data.SubjectKind
import `in`.abhaynayan.app.data.SyncCounts
import `in`.abhaynayan.app.interop.ReferralSummary
import `in`.abhaynayan.app.interop.ReportDocument
import `in`.abhaynayan.app.services.PdfReportWriter
import java.io.File
import `in`.abhaynayan.app.net.ApiResult
import `in`.abhaynayan.app.net.BackendUrlPolicy
import `in`.abhaynayan.app.net.DashboardSummary
import `in`.abhaynayan.app.net.Payloads
import `in`.abhaynayan.app.services.CounselOutcome
import `in`.abhaynayan.app.services.CounselSource
import `in`.abhaynayan.app.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class WorkflowDraft(
    val type: ScreeningType,
    val stage: WorkflowStage = WorkflowStage.PROFILE,
    val patientId: String? = null,
    val form: MetadataForm = MetadataForm(),
    val formErrors: Map<String, String> = emptyMap(),
    val assessment: CaptureAssessment? = null,
    val assessing: Boolean = false,
    val captureError: String? = null,
    val regionConfirmed: Boolean = false,
    val demoCaseId: String? = null,
    val preview: ScreeningEntity? = null,
    val modelStatus: ModelStatus? = null,
    /** Research-only NJN dataset-class score shown beside (never instead of) the result; not saved or synced. */
    val jaundiceClassScore: JaundiceClassScore? = null,
    val jaundiceClassError: String? = null,
    val analysisError: String? = null,
    val saving: Boolean = false,
)

enum class BackendState { NOT_CONFIGURED, UNKNOWN, CHECKING, REACHABLE, UNREACHABLE }
data class BackendStatus(val state: BackendState, val message: String = "", val geminiConfigured: Boolean? = null)

data class CounselUi(val outcome: CounselOutcome, val loading: Boolean)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AbhayApp
    private val repo: ScreeningRepository = app.repository

    private val stack = MutableStateFlow(BackStack())
    val backStack: StateFlow<BackStack> = stack.asStateFlow()

    private val draftState = MutableStateFlow<WorkflowDraft?>(null)
    val draft: StateFlow<WorkflowDraft?> = draftState.asStateFlow()

    val patients: StateFlow<List<PatientEntity>> = repo.patients.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val records: StateFlow<List<ScreeningEntity>> = repo.records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncCounts: StateFlow<SyncCounts> = repo.syncCounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncCounts(0, 0, 0, 0))
    val settings = app.settings.settings
    val online: StateFlow<Boolean> = app.network.online

    private val backend = MutableStateFlow(BackendStatus(BackendState.UNKNOWN))
    val backendStatus: StateFlow<BackendStatus> = backend.asStateFlow()

    private val message = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = message.asStateFlow()

    private val counsel = MutableStateFlow<Map<Pair<String, Language>, CounselUi>>(emptyMap())
    val counselState: StateFlow<Map<Pair<String, Language>, CounselUi>> = counsel.asStateFlow()

    private val dashboard = MutableStateFlow<DashboardSummary?>(null)
    val serverDashboard: StateFlow<DashboardSummary?> = dashboard.asStateFlow()

    private val pdfBusy = MutableStateFlow(false)
    val generatingPdf: StateFlow<Boolean> = pdfBusy.asStateFlow()

    private val syncBusy = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = syncBusy.asStateFlow()

    private var assessJob: Job? = null

    private val assetSource = AssetSource { path ->
        try { application.assets.open(path).bufferedReader().use { it.readText() } } catch (_: java.io.IOException) { null }
    }
    private val edge = MutableStateFlow(EdgeModelLoader.load(assetSource, app.settings.settings.value.experimentalModels))

    val hemoglobinStatus: ModelStatus get() = edge.value.hemoglobin.status()
    val bilirubinStatus: ModelStatus = ModelRegistry.status(Biomarker.BILIRUBIN)
    val jaundiceClassAvailable: Boolean get() = edge.value.jaundiceClass != null
    val jaundiceClassProblems: List<String> get() = edge.value.jaundiceClassProblems

    /** Reloads first (two small JSON assets) so screens recomposed by the settings change see the new status. */
    fun setExperimentalModels(enabled: Boolean) {
        edge.value = EdgeModelLoader.load(assetSource, enabled)
        app.settings.update { it.copy(experimentalModels = enabled) }
    }

    init {
        viewModelScope.launch { repo.ensureDemoProfiles() }
        checkBackend()
        viewModelScope.launch { online.collect { if (it) checkBackend() } }
    }

    // ----- navigation -----------------------------------------------------------------------------

    fun navigate(screen: Screen) = stack.update { it.push(screen) }

    /** Returns false when the system should handle Back (we are at Home). */
    fun back(): Boolean {
        val current = stack.value.current
        if (current == Screen.Workflow) {
            val d = draftState.value
            val previous = d?.let { StageFlow.previous(it.stage) }
            if (d != null && previous != null && d.stage != WorkflowStage.RESULT) {
                setStage(previous); return true
            }
            if (d?.stage == WorkflowStage.RESULT) { setStage(WorkflowStage.ANALYSIS); return true }
            cancelWorkflow(); return true
        }
        if (!stack.value.canGoBack) return false
        stack.update { it.pop() }
        return true
    }

    fun consumeMessage() { message.value = null }
    private fun say(text: String) { message.value = text }

    // ----- workflow -------------------------------------------------------------------------------

    fun startWorkflow(type: ScreeningType) {
        assessJob?.cancel()
        draftState.value = WorkflowDraft(type)
        navigate(Screen.Workflow)
    }

    fun cancelWorkflow() {
        assessJob?.cancel()
        draftState.value = null // drops in-memory frames; nothing was written to storage
        stack.update { it.resetTo(Screen.Home) }
    }

    private fun edit(transform: (WorkflowDraft) -> WorkflowDraft) = draftState.update { it?.let(transform) }

    fun setStage(stage: WorkflowStage) = edit { it.copy(stage = stage, analysisError = null) }

    fun selectPatient(id: String) = edit { it.copy(patientId = id, demoCaseId = null) }

    fun createPatient(nickname: String, synthetic: Boolean, consent: Boolean) {
        val d = draftState.value ?: return
        if (!consent) { say("Consent is required before saving a profile."); return }
        viewModelScope.launch {
            val kind = if (d.type == ScreeningType.MATERNAL_ANEMIA) SubjectKind.MATERNAL else SubjectKind.NEWBORN
            val patient = repo.createPatient(nickname, synthetic, consent, kind)
            edit { it.copy(patientId = patient.patientId) }
        }
    }

    fun profileContinue() {
        val d = draftState.value ?: return
        if (d.patientId == null) { say("Choose or create a profile first."); return }
        setStage(WorkflowStage.DETAILS)
    }

    fun updateForm(form: MetadataForm) = edit { it.copy(form = form, formErrors = if (it.formErrors.isEmpty()) emptyMap() else MetadataValidator.validate(it.type, form)) }

    fun submitDetails() {
        val d = draftState.value ?: return
        val errors = MetadataValidator.validate(d.type, d.form)
        edit { it.copy(formErrors = errors) }
        if (errors.isEmpty()) setStage(WorkflowStage.PREPARE)
    }

    fun onCaptureFailed(text: String) = edit { it.copy(captureError = text) }

    fun onFramesCaptured(frames: CapturedFrames, synthetic: Boolean) {
        edit { it.copy(assessing = true, captureError = null, assessment = null, regionConfirmed = false, preview = null, stage = WorkflowStage.QUALITY) }
        assessJob?.cancel()
        assessJob = viewModelScope.launch {
            val assessment = try {
                withContext(Dispatchers.Default) { WorkflowEngine.assess(frames, synthetic) }
            } catch (e: IllegalArgumentException) {
                SafeLog.w("Assessment failed", e)
                edit { it.copy(assessing = false, captureError = "The image could not be processed. Retake.", stage = WorkflowStage.CAPTURE) }
                return@launch
            }
            // The synthetic chart has no anatomy; its fixed guide region is confirmed automatically.
            edit { it.copy(assessing = false, assessment = assessment, regionConfirmed = synthetic && assessment.quality.passed) }
        }
    }

    fun useSyntheticChart() = onFramesCaptured(WorkflowEngine.syntheticFrames(), synthetic = true)

    fun onImagePicked(uri: Uri) {
        edit { it.copy(captureError = null) }
        viewModelScope.launch {
            val image = withContext(Dispatchers.IO) { decodeImage(app.contentResolver, uri) }
            when (image) {
                is DecodeResult.Ok -> onFramesCaptured(CapturedFrames(
                    ambient = image.image, flash = null,
                    ambientMeta = FrameMetadata(null, null, null, null, "imported image"), flashMeta = null,
                    capabilities = null, notes = listOf("Imported image: no flash comparison; orientation as stored in the file."),
                    source = "Imported ${image.mime} image",
                ), synthetic = false)
                is DecodeResult.Error -> onCaptureFailed(image.message)
            }
        }
    }

    fun retake() {
        assessJob?.cancel()
        edit { it.copy(assessment = null, assessing = false, regionConfirmed = false, preview = null, captureError = null, stage = WorkflowStage.CAPTURE) }
    }

    fun confirmRegion(confirmed: Boolean) = edit { it.copy(regionConfirmed = confirmed) }
    fun selectDemoCase(id: String?) = edit { it.copy(demoCaseId = id) }

    fun runAnalysis() {
        val d = draftState.value ?: return
        val assessment = d.assessment ?: return
        viewModelScope.launch {
            val patient = d.patientId?.let { repo.patient(it) } ?: run { say("Profile not found."); return@launch }
            val s = settings.value
            val outcome = withContext(Dispatchers.Default) {
                WorkflowEngine.analyze(
                    type = d.type, patient = patient,
                    metadata = MetadataValidator.toMetadata(d.type, d.form),
                    assessment = assessment, regionConfirmed = d.regionConfirmed,
                    demoCaseId = d.demoCaseId.takeIf { patient.isSyntheticDemo },
                    hemoglobinModel = edge.value.hemoglobin,
                    bilirubinModel = UnavailableBilirubinModel(bilirubinStatus),
                    workerId = s.workerLabel, phc = s.phcLabel,
                    screeningId = ScreeningRepository.newScreeningId(),
                )
            }
            // The jaundice-class score is separate research output: it never fills the bilirubin estimate.
            val scorer = edge.value.jaundiceClass
            val (classScore, classError) = if (outcome is AnalysisOutcome.Ready && d.type.biomarker == Biomarker.BILIRUBIN && scorer != null) {
                withContext(Dispatchers.Default) {
                    try {
                        val input = WorkflowEngine.modelInput(assessment) ?: error("No usable region.")
                        scorer.score(input) to null
                    } catch (e: RuntimeException) {
                        null to "The research jaundice-class model could not process this image (${e.javaClass.simpleName}). No score is shown."
                    }
                }
            } else null to null
            when (outcome) {
                is AnalysisOutcome.Ready -> edit { it.copy(preview = outcome.record, modelStatus = outcome.modelStatus, analysisError = null,
                    jaundiceClassScore = classScore, jaundiceClassError = classError, stage = WorkflowStage.RESULT) }
                is AnalysisOutcome.Blocked -> edit { it.copy(analysisError = outcome.message) }
                is AnalysisOutcome.InferenceFailed -> edit { it.copy(analysisError = outcome.message) }
            }
        }
    }

    fun saveResult() {
        val d = draftState.value ?: return
        val record = d.preview ?: return
        if (d.saving) return // ignore double taps
        edit { it.copy(saving = true) }
        viewModelScope.launch {
            val stored = repo.save(record)
            draftState.value = null // release frames
            stack.value = BackStack().resetTo(Screen.Record(stored.screeningId))
            say(if (stored.syncState == "PENDING") "Saved on this phone. It will sync when online." else "Saved on this phone only.")
        }
    }

    // ----- records, sync, counselling, referral ---------------------------------------------------

    fun record(id: String) = repo.record(id)
    fun referrals(id: String) = repo.referrals(id)

    fun syncNow() {
        if (syncBusy.value) return
        if (app.settings.activeBaseUrl() == null) { say("No backend configured. Records stay on this phone."); return }
        syncBusy.value = true
        viewModelScope.launch {
            try {
                val outcome = app.syncEngine.run()
                say(outcome.message)
                if (outcome.retryLater) repo.syncNow()
            } finally { syncBusy.value = false }
        }
    }

    fun retryFailed(id: String) = viewModelScope.launch { repo.retryFailed(id); say("Queued for another sync attempt.") }

    /** Fetches counselling text; anything other than Gemini wording is fetched again (e.g. after quota resets). */
    fun loadCounsel(record: ScreeningEntity, language: Language) {
        val key = record.screeningId to language
        val type = ScreeningType.fromWire(record.screeningType) ?: return
        val mode = `in`.abhaynayan.app.core.ResultMode.entries.firstOrNull { it.name == record.resultMode } ?: `in`.abhaynayan.app.core.ResultMode.DISABLED_NO_VALID_MODEL
        val existing = counsel.value[key]
        if (existing != null && (existing.loading || existing.outcome.source == CounselSource.SERVER_GEMINI)) return
        counsel.update { it + (key to CounselUi(existing?.outcome ?: app.counselling.local(type, mode, language), loading = true)) }
        viewModelScope.launch {
            val outcome = app.counselling.fetch(record, language, online.value && app.settings.activeBaseUrl() != null)
            counsel.update { it + (key to CounselUi(outcome, loading = false)) }
        }
    }

    /**
     * Builds the on-device PDF report (any result mode, works offline) and hands the file to [onReady].
     * Uses the counselling text currently shown for [language] (server wording if it arrived, else on-device).
     */
    fun exportReport(record: ScreeningEntity, language: Language, onReady: (File) -> Unit) {
        if (pdfBusy.value) return // ignore double taps
        pdfBusy.value = true
        viewModelScope.launch {
            try {
                val type = ScreeningType.fromWire(record.screeningType) ?: ScreeningType.MATERNAL_ANEMIA
                val mode = `in`.abhaynayan.app.core.ResultMode.entries.firstOrNull { it.name == record.resultMode } ?: `in`.abhaynayan.app.core.ResultMode.DISABLED_NO_VALID_MODEL
                val counselOutcome = counsel.value[record.screeningId to language]?.outcome ?: app.counselling.local(type, mode, language)
                val doc = ReportDocument.from(record, language, counselOutcome.text, counselOutcome.source.label, BuildConfig.VERSION_NAME)
                val file = withContext(Dispatchers.IO) { PdfReportWriter.write(app, doc, record.screeningId) }
                onReady(file)
            } catch (e: Exception) {
                SafeLog.w("PDF report failed", e)
                say("The PDF report could not be created. You can still share the text summary.")
            } finally {
                pdfBusy.value = false
            }
        }
    }

    fun reportText(record: ScreeningEntity, language: Language): String {
        val type = ScreeningType.fromWire(record.screeningType) ?: ScreeningType.MATERNAL_ANEMIA
        val mode = `in`.abhaynayan.app.core.ResultMode.entries.firstOrNull { it.name == record.resultMode } ?: `in`.abhaynayan.app.core.ResultMode.DISABLED_NO_VALID_MODEL
        val c = counsel.value[record.screeningId to language]?.outcome ?: app.counselling.local(type, mode, language)
        return ReportDocument.from(record, language, c.text, c.source.label, BuildConfig.VERSION_NAME).toPlainText()
    }

    fun createReferral(record: ScreeningEntity, language: Language, counsellingText: String, counsellingMode: String = "LOCAL_FALLBACK") {
        viewModelScope.launch {
            val summary = ReferralSummary.from(record, language)
            val id = ScreeningRepository.newReferralId()
            val patient = repo.patient(record.patientId)
            val eligible = Payloads.syncEligibility(record, patient) is Payloads.Eligibility.Eligible
            var entity = ReferralEntity(id, record.screeningId, Instant.now().toString(), language.code, summary.urgency.name,
                summary.nextStep, if (eligible) "PENDING" else "LOCAL_ONLY")
            repo.saveReferral(entity)
            if (!eligible) { say("Referral saved on this phone. Share the summary with the facility."); return@launch }
            val contract = when (val c = app.api.contract()) {
                is ApiResult.Success -> c.value
                is ApiResult.Failure -> {
                    repo.saveReferral(entity.copy(serverState = "FAILED", lastError = c.userMessage))
                    say("Referral saved on this phone. ${c.userMessage}")
                    return@launch
                }
            }
            val request = Payloads.referralRequest(id, record, settings.value.workerLabel, language, counsellingText, summary.nextStep, contract, counsellingMode)
            entity = when (val result = app.api.createReferral(request)) {
                is ApiResult.Success -> entity.copy(serverState = "CREATED", pdfPath = result.value.pdfPath)
                is ApiResult.Failure -> entity.copy(serverState = "FAILED", lastError = result.userMessage)
            }
            repo.saveReferral(entity)
            say(if (entity.serverState == "CREATED") "Referral created on the server." else "Referral saved on this phone. ${entity.lastError}")
        }
    }

    fun absoluteUrl(path: String): String? = app.api.absolute(path)

    // ----- settings -------------------------------------------------------------------------------

    fun setLanguage(language: Language) = app.settings.update { it.copy(language = language) }

    fun saveBackend(url: String, enabled: Boolean): String? {
        if (!enabled) {
            app.settings.update { it.copy(backendEnabled = false, backendExplicit = true) }
            backend.value = BackendStatus(BackendState.NOT_CONFIGURED, "Backend disabled. Everything stays on this phone.")
            return null
        }
        return when (val r = BackendUrlPolicy.validate(url, BuildConfig.DEBUG)) {
            is BackendUrlPolicy.Result.Invalid -> r.message
            is BackendUrlPolicy.Result.Valid -> {
                app.settings.update { it.copy(backendUrl = r.normalized, backendEnabled = true, backendExplicit = true) }
                checkBackend(); repo.syncNow(); null
            }
        }
    }

    fun saveWorker(worker: String, phc: String) {
        val cleanWorker = worker.trim().filter { it.isLetterOrDigit() || it in "-_." }.take(64).ifBlank { "demo-worker" }
        app.settings.update { it.copy(workerLabel = cleanWorker, phcLabel = phc.trim().take(100).ifBlank { "Demo PHC" }) }
        say("Saved. Worker label: $cleanWorker")
    }

    fun checkBackend() {
        if (app.settings.activeBaseUrl() == null) {
            backend.value = BackendStatus(BackendState.NOT_CONFIGURED, "No backend configured. Records stay on this phone."); return
        }
        backend.value = BackendStatus(BackendState.CHECKING, "Checking server… (a sleeping free-tier server can take up to a minute to wake)")
        viewModelScope.launch {
            backend.value = when (val r = app.api.health()) {
                is ApiResult.Success -> BackendStatus(BackendState.REACHABLE,
                    "Server reachable (${r.value.mode}${r.value.contractVersion?.let { ", API contract $it" } ?: ", pre-1.0 API"}).", r.value.geminiConfigured)
                is ApiResult.Failure -> BackendStatus(BackendState.UNREACHABLE, r.userMessage)
            }
            if (backend.value.state == BackendState.REACHABLE) {
                (app.api.dashboard() as? ApiResult.Success)?.let { dashboard.value = it.value }
            }
        }
    }

    fun deleteAllLocalData() {
        viewModelScope.launch {
            repo.deleteAllLocalData()
            withContext(Dispatchers.IO) { PdfReportWriter.clear(app) }
            counsel.value = emptyMap()
            repo.ensureDemoProfiles()
            say("All screening records and profiles were deleted from this phone.")
        }
    }

    // ----- image import ---------------------------------------------------------------------------

    private sealed class DecodeResult {
        data class Ok(val image: RgbImage, val mime: String) : DecodeResult()
        data class Error(val message: String) : DecodeResult()
    }

    private fun decodeImage(resolver: ContentResolver, uri: Uri): DecodeResult {
        val mime = resolver.getType(uri)
        if (mime !in SUPPORTED_MIME) return DecodeResult.Error("Unsupported image format. Use a JPEG, PNG, or WebP photo.")
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return DecodeResult.Error("The file could not be read as an image.")
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMPORT_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return DecodeResult.Error("The file could not be read as an image.")
            val scale = MAX_IMPORT_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
            val bitmap = if (scale < 1f) decoded.scale((decoded.width * scale).toInt(), (decoded.height * scale).toInt()).also { if (it != decoded) decoded.recycle() } else decoded
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val image = RgbImage(bitmap.width, bitmap.height, pixels)
            bitmap.recycle()
            DecodeResult.Ok(image, mime!!.substringAfter('/').uppercase())
        } catch (e: OutOfMemoryError) {
            DecodeResult.Error("The image is too large for this phone. Use the camera instead.")
        } catch (e: Exception) {
            DecodeResult.Error("The file could not be read as an image.")
        }
    }

    private companion object {
        val SUPPORTED_MIME = setOf("image/jpeg", "image/png", "image/webp")
        const val MAX_IMPORT_SIDE = 640
    }
}
