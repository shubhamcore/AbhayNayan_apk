package `in`.abhaynayan.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.core.LocalCounselling
import `in`.abhaynayan.app.core.ModelStatus
import `in`.abhaynayan.app.core.ReferralRules
import `in`.abhaynayan.app.core.ReferralUrgency
import `in`.abhaynayan.app.core.ResultMode
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.data.ScreeningEntity
import `in`.abhaynayan.app.data.SyncState
import `in`.abhaynayan.app.interop.FhirExport
import `in`.abhaynayan.app.interop.ReferralSummary
import `in`.abhaynayan.app.services.CounselingSpeaker
import `in`.abhaynayan.app.services.SpeechState
import java.util.Locale

/** Shared by the unsaved result preview and the saved record view. */
@Composable
fun ResultDetails(record: ScreeningEntity, modelStatus: ModelStatus?) {
    val s = LocalStrings.current
    val type = ScreeningType.fromWire(record.screeningType) ?: ScreeningType.MATERNAL_ANEMIA
    val mode = ResultMode.entries.firstOrNull { it.name == record.resultMode } ?: ResultMode.DISABLED_NO_VALID_MODEL
    val urgency = ReferralUrgency.entries.firstOrNull { it.name == record.referralUrgency } ?: ReferralUrgency.CONFIRMATORY_TESTING
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeBanner(mode)
        val estimate = record.estimate
        if (mode.producesEstimate && estimate != null) {
            val (bg, fg) = modeColors(mode)
            SectionCard(container = bg, border = fg.copy(alpha = .35f)) {
                if (mode == ResultMode.DEMO_SYNTHETIC) Text("FIXED DEMO VALUE · NOT FROM IMAGE · NOT A LAB RESULT", style = MaterialTheme.typography.labelSmall, color = fg)
                Text("${"%.1f".format(Locale.ROOT, estimate)} ${record.unit}", style = MaterialTheme.typography.headlineSmall, color = fg)
                Text("${type.biomarker.displayName} · ${record.demoTag}", style = MaterialTheme.typography.bodyMedium, color = fg)
            }
        } else {
            SectionCard(title = s.noEstimateTitle) {
                InfoText("No ${type.biomarker.displayName.lowercase()} value was produced, because no valid model is available for this measurement.")
                modelStatus?.reasons?.forEach { InfoText("• $it", Palette.Muted) }
                if (type == ScreeningType.NEONATAL_JAUNDICE) InfoText("A phone photo is not a substitute for a bilirubin test or the newborn-care assessment protocol.")
            }
        }
        val urgent = urgency == ReferralUrgency.URGENT_REFERRAL
        SectionCard(
            title = "Next step",
            container = if (urgent) Palette.RedSurface else Palette.Surface,
            border = if (urgent) Palette.RedText.copy(alpha = .4f) else Palette.Line,
        ) {
            Text(urgency.label.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = if (urgent) Palette.RedText else Palette.Teal)
            Text(LocalCounselling.urgencyLine(urgency, s.language), style = MaterialTheme.typography.bodyLarge, color = if (urgent) Palette.RedText else Palette.Navy)
            Text(ReferralRules.REVIEW_STATUS, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        SectionCard(title = "Record") {
            KeyValue("Screening", type.displayName)
            KeyValue("Record ID", record.screeningId)
            KeyValue("Patient ID (pseudonymous)", record.patientId)
            KeyValue("Time", formatTime(record.createdAtUtc))
            KeyValue("Processing", if (mode == ResultMode.DISABLED_NO_VALID_MODEL) "Image checks completed; estimate disabled" else "Completed (${mode.name})")
            KeyValue("Provenance", if (mode == ResultMode.DEMO_SYNTHETIC) "Fixed fixture set ${record.modelVersion}; case ${record.caseId ?: record.demoTag}" else "Model: ${record.modelVersion}")
            KeyValue("Image quality", record.qualityStatus)
            KeyValue("Flash comparison", record.differentialStatus)
            KeyValue("Capture source", record.captureSource.ifBlank { "Not recorded" })
        }
        SectionCard(title = "Limitations") {
            InfoText("• This prototype is not clinically validated and is not a diagnosis.")
            InfoText("• Image checks measure photo quality only, not health.")
            InfoText(if (type == ScreeningType.MATERNAL_ANEMIA) "• Confirm hemoglobin with an appropriate laboratory or approved point-of-care test." else "• Confirm bilirubin with an appropriate test; urgency depends on age in hours and danger signs.")
            if (mode == ResultMode.DEMO_SYNTHETIC) InfoText("• The value is synthetic and must never be used for this person.")
            InfoText("• No photo is stored with this record.")
        }
    }
}

@Composable
fun RecordScreen(id: String, vm: AppViewModel, speaker: CounselingSpeaker, onBack: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val record by vm.record(id).collectAsState(initial = null)
    val referrals by vm.referrals(id).collectAsState(initial = emptyList())
    val settings by vm.settings.collectAsState()
    val counselMap by vm.counselState.collectAsState()
    val speech by speaker.state.collectAsState()
    var language by remember { mutableStateOf(settings.language) }

    Column(Modifier.fillMaxSize()) {
        TopBar(s.counselling + " · " + s.referral, record?.let { ScreeningType.fromWire(it.screeningType)?.displayName }, onBack)
        val r = record
        if (r == null) {
            Column(Modifier.fillMaxWidth()) { InfoText("Record not found on this phone.") }
            return@Column
        }
        LaunchedEffect(r.screeningId, language) { vm.loadCounsel(r, language) }
        val counsel = counselMap[r.screeningId to language]
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SyncStatusCard(r, onRetry = { vm.retryFailed(r.screeningId) }, onSync = { vm.syncNow() }) }
            item { ResultDetails(r, null) }
            item {
                SectionCard(title = s.counselling) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Language.entries.forEach { lang ->
                            FilterChip(selected = lang == language, onClick = { speaker.stop(); language = lang },
                                label = { Text(lang.nativeLabel) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    if (counsel == null) InfoText("Loading…")
                    counsel?.let { c ->
                        if (c.loading) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.heightIn(max = 20.dp), strokeWidth = 2.dp)
                            InfoText("Checking the server for counselling text; showing on-device text meanwhile.", Palette.Muted)
                        }
                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(c.outcome.text.summary, style = MaterialTheme.typography.bodyLarge, color = Palette.Navy)
                            Text(c.outcome.text.riskExplanation, style = MaterialTheme.typography.bodyLarge, color = Palette.Body)
                            Text(c.outcome.text.script, style = MaterialTheme.typography.bodyLarge, color = Palette.Body)
                            Text(c.outcome.text.safetyNote, style = MaterialTheme.typography.bodyMedium, color = Palette.AmberText)
                        }
                        Chip(c.outcome.source.label, Palette.TealSoft, Palette.Navy)
                        c.outcome.note?.let { InfoText(it, Palette.Muted) }
                        SpeechControls(speech, onPlay = { speaker.speak(c.outcome.text.fullText, language) }, onStop = { speaker.stop() })
                    }
                }
            }
            item {
                val summary = remember(r, language) { ReferralSummary.from(r, language) }
                SectionCard(title = s.referral) {
                    Text(summary.urgency.label, style = MaterialTheme.typography.titleMedium, color = Palette.Navy)
                    InfoText(summary.nextStep)
                    PrimaryButton(s.shareReferral, { share(context, "AbhayNayan referral summary", summary.toText()) }, Modifier.fillMaxWidth())
                    SecondaryButton("Share FHIR-style JSON (pseudonymous)", {
                        share(context, "AbhayNayan FHIR-style bundle", FhirExport.bundle(r, settings.phcLabel).toString(2))
                    }, Modifier.fillMaxWidth())
                    SecondaryButton("Save referral record", {
                        vm.createReferral(r, language, counsel?.outcome?.text?.script.orEmpty())
                    }, Modifier.fillMaxWidth())
                    referrals.forEach { ref ->
                        KeyValue("Referral ${ref.referralId.take(12)}…", "${formatTime(ref.createdAtUtc)} · ${ref.serverState.lowercase().replace('_', ' ')}" + (ref.lastError?.let { " · $it" } ?: ""))
                        ref.pdfPath?.let { path ->
                            vm.absoluteUrl(path)?.let { url -> SecondaryButton("Open server PDF", { open(context, url) }, Modifier.fillMaxWidth()) }
                        }
                    }
                }
            }
            item { SecondaryButton(s.home, onBack, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun SpeechControls(state: SpeechState, onPlay: () -> Unit, onStop: () -> Unit) {
    val s = LocalStrings.current
    ActionRow {
        PrimaryButton(if (state.speaking) "Playing…" else "▶ ${s.play}", onPlay, Modifier.weight(1f), enabled = state.ready && !state.speaking)
        SecondaryButton("■ ${s.stop}", onStop, Modifier.weight(1f), enabled = state.speaking)
    }
    state.note?.let { InfoText(it, Palette.Muted) }
}

@Composable
private fun SyncStatusCard(record: ScreeningEntity, onRetry: () -> Unit, onSync: () -> Unit) {
    val (label, ok) = syncLabel(record.syncState)
    SectionCard(container = if (ok) Palette.TealSoft else Palette.Surface) {
        Text("Saved on this phone · $label", style = MaterialTheme.typography.titleSmall, color = if (ok) Palette.GreenText else Palette.Navy)
        record.serverConfirmedAtUtc?.let { InfoText("Server confirmed: ${formatTime(it)}") }
        record.lastSyncError?.let { InfoText(it, if (record.syncState == SyncState.FAILED) Palette.RedText else Palette.Muted) }
        if (record.syncState == SyncState.FAILED) SecondaryButton("Retry sync", onRetry, Modifier.fillMaxWidth())
        if (record.syncState == SyncState.PENDING) SecondaryButton("Sync now", onSync, Modifier.fillMaxWidth())
    }
}

private fun share(context: Context, subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, subject))
}

private fun open(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } catch (_: ActivityNotFoundException) { }
}
