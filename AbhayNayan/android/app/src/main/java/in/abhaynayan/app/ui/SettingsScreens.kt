package `in`.abhaynayan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import `in`.abhaynayan.app.BuildConfig
import `in`.abhaynayan.app.core.ModelRegistry
import `in`.abhaynayan.app.core.ModelStatus
import `in`.abhaynayan.app.core.ReferralRules
import `in`.abhaynayan.app.net.AppSettings
import `in`.abhaynayan.app.net.DashboardSummary

@Composable
fun SettingsScreen(
    settings: AppSettings,
    backend: BackendStatus,
    dashboard: DashboardSummary?,
    hbStatus: ModelStatus,
    biliStatus: ModelStatus,
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    var url by rememberSaveable { mutableStateOf(settings.backendUrl.orEmpty()) }
    var enabled by rememberSaveable { mutableStateOf(settings.backendEnabled) }
    var urlError by rememberSaveable { mutableStateOf<String?>(null) }
    var worker by rememberSaveable { mutableStateOf(settings.workerLabel) }
    var phc by rememberSaveable { mutableStateOf(settings.phcLabel) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(s.settings, onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Backend server") {
                    Row(Modifier.fillMaxWidth().heightIn(min = MinTouch).toggleable(enabled, role = Role.Switch) { enabled = it }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Use a backend for sync and counselling", style = MaterialTheme.typography.bodyLarge, color = Palette.Navy, modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = null)
                    }
                    OutlinedTextField(url, { url = it.trim(); urlError = null }, label = { Text("Base URL") }, singleLine = true, enabled = enabled,
                        isError = urlError != null, supportingText = { Text(urlError ?: "Deployed: https://… · Emulator: http://10.0.2.2:8000 · Phone via adb reverse: http://127.0.0.1:8000 (debug builds only)") },
                        modifier = Modifier.fillMaxWidth())
                    ActionRow {
                        PrimaryButton("Save", { urlError = vm.saveBackend(url, enabled) }, Modifier.weight(1f))
                        SecondaryButton("Test connection", { vm.checkBackend() }, Modifier.weight(1f), enabled = enabled)
                    }
                    InfoText(backend.message, if (backend.state == BackendState.REACHABLE) Palette.GreenText else Palette.Body)
                    backend.geminiConfigured?.let { InfoText("Server counselling assistant (Gemini) configured: ${if (it) "yes" else "no — reviewed fallback text is used"}", Palette.Muted) }
                    InfoText("No API key is stored in this app. Photos are never uploaded. Only pseudonymous synthetic demo records are synced.", Palette.Muted)
                }
            }
            if (dashboard != null) item {
                SectionCard(title = "Server summary (synthetic demo data)") {
                    KeyValue("Screenings on server", dashboard.totalScreenings.toString())
                    KeyValue("Today", dashboard.todayScreenings.toString())
                    KeyValue("Referral briefs", dashboard.referrals.toString())
                }
            }
            item {
                SectionCard(title = "Worker label") {
                    InfoText("Use a pseudonymous code (for example ASHA-017), not a personal name. It is attached to synced demo records.")
                    OutlinedTextField(worker, { worker = it.take(64) }, label = { Text("Worker code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(phc, { phc = it.take(100) }, label = { Text("PHC / facility label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SecondaryButton("Save worker details", { vm.saveWorker(worker, phc) }, Modifier.fillMaxWidth())
                }
            }
            item { ModelStatusCard(hbStatus, biliStatus) }
            item {
                SectionCard(title = "About") {
                    KeyValue("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${if (BuildConfig.DEBUG) "debug build" else "release build"}")
                    KeyValue("Application ID", BuildConfig.APPLICATION_ID)
                    KeyValue("Local storage", "Room database on this phone; not encrypted by the app. Use synthetic data for demos.")
                }
            }
            item {
                SectionCard(title = "Delete data") {
                    InfoText("Deletes all profiles, screening records, and referral records on this phone. Records already synced stay on the server.")
                    DangerButton("Delete all local data", { confirmDelete = true }, Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all local data?") },
            text = { Text("This cannot be undone. Unsynced records will be lost.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAllLocalData() }) { Text("Delete", color = Palette.RedText) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ModelStatusCard(hbStatus: ModelStatus, biliStatus: ModelStatus) {
    SectionCard(title = "Model status and provenance") {
        listOf(hbStatus, biliStatus).forEach { st ->
            KeyValue(st.biomarker.displayName, "${st.mode.name} — ${st.mode.label}")
        }
        ModelRegistry.inspectedCandidates.forEach { c ->
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, color = Palette.Navy)
                InfoText("${c.biomarker.displayName} · ${c.kind}", Palette.Muted)
                InfoText("Inputs: ${c.inputs}", Palette.Muted)
                InfoText("Evidence: ${c.evidence}", Palette.Muted)
                InfoText("Not deployed: ${c.whyNotDeployed}")
            }
        }
        InfoText("Validated mode cannot be switched on in this build. It requires a model card, validation evidence, and authorization.", Palette.Muted)
    }
}

@Composable
fun HelpScreen(hbStatus: ModelStatus, biliStatus: ModelStatus, onBack: () -> Unit) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxSize()) {
        TopBar(s.help, onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SafetyNote(s.safetyShort) }
            item {
                SectionCard(title = "What this app is") {
                    InfoText("A guided workflow for frontline health workers: it records minimal details, checks photo quality on the phone, records danger signs, and gives counselling text and a referral summary.")
                    InfoText("It does NOT measure hemoglobin or bilirubin. No validated model exists for either. Demo profiles can show fixed synthetic values that are clearly labelled and never come from the photo.")
                }
            }
            item {
                SectionCard(title = "Result modes") {
                    `in`.abhaynayan.app.core.ResultMode.entries.forEach { KeyValue(it.name, it.description) }
                }
            }
            item {
                SectionCard(title = "When to refer urgently") {
                    InfoText("Any danger sign ticked in the form, or visible jaundice in a baby younger than 24 hours, shows an URGENT referral message. These rules can only raise urgency; the app never says that a person is well.")
                    InfoText(ReferralRules.REVIEW_STATUS, Palette.Muted)
                }
            }
            item {
                SectionCard(title = "Privacy") {
                    InfoText("• Photos stay in memory on this phone and are discarded after the checks.")
                    InfoText("• Profiles use random IDs. Nicknames never leave the phone.")
                    InfoText("• Only synthetic demo records are sent to the backend, with a pseudonymous ID and no photo.")
                    InfoText("• The counselling assistant receives only the screening type, demo value, language, and age/pregnancy fields — never names or photos.")
                }
            }
            item {
                SectionCard(title = "Offline use") {
                    InfoText("Screening, image checks, records, counselling text, and referral summaries work without internet. Demo records wait in the sync queue and are sent automatically when the phone is online and a backend is configured.")
                }
            }
            item { ModelStatusCard(hbStatus, biliStatus) }
        }
    }
}
