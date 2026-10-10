package `in`.abhaynayan.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import `in`.abhaynayan.app.BuildConfig
import `in`.abhaynayan.app.core.Language
import `in`.abhaynayan.app.core.ModelStatus
import `in`.abhaynayan.app.core.ResultMode
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.data.ScreeningEntity
import `in`.abhaynayan.app.data.SyncCounts
import `in`.abhaynayan.app.data.SyncState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault())

fun formatTime(utc: String): String = try { DATE.format(Instant.parse(utc)) } catch (_: Exception) { utc }

@Composable
fun HomeScreen(
    records: List<ScreeningEntity>,
    counts: SyncCounts,
    online: Boolean,
    backend: BackendStatus,
    language: Language,
    syncing: Boolean,
    hbStatus: ModelStatus,
    biliStatus: ModelStatus,
    onLanguage: (Language) -> Unit,
    onStart: (ScreeningType) -> Unit,
    onOpenRecord: (String) -> Unit,
    onAllRecords: () -> Unit,
    onSync: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxSize()) {
        TopBar("AbhayNayan", s.tagline) { StatusDot(online); Spacer(Modifier.width(10.dp)) }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.languageLabel, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Language.entries.forEach { lang ->
                            FilterChip(selected = lang == language, onClick = { onLanguage(lang) },
                                label = { Text(lang.nativeLabel, style = MaterialTheme.typography.labelLarge) },
                                modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
            }
            item { Text(s.startScreening, style = MaterialTheme.typography.titleLarge, color = Palette.Navy) }
            item { WorkflowEntry(s.maternalTitle, s.maternalSubtitle, hbStatus) { onStart(ScreeningType.MATERNAL_ANEMIA) } }
            item { WorkflowEntry(s.neonatalTitle, s.neonatalSubtitle, biliStatus) { onStart(ScreeningType.NEONATAL_JAUNDICE) } }
            item { SyncCard(counts, backend, syncing, onSync) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.recentRecords, style = MaterialTheme.typography.titleMedium, color = Palette.Navy, modifier = Modifier.weight(1f))
                    if (records.isNotEmpty()) SecondaryButton(s.allRecords, onAllRecords)
                }
            }
            if (records.isEmpty()) item { InfoText(s.noRecords) }
            items(records.take(5), key = { it.screeningId }) { RecordRow(it) { onOpenRecord(it.screeningId) } }
            item {
                ActionRow {
                    SecondaryButton(s.help, onHelp, Modifier.weight(1f))
                    SecondaryButton(s.settings, onSettings, Modifier.weight(1f))
                }
            }
            item { SafetyNote(s.safetyShort) }
            item {
                Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Hb model: ${hbStatus.mode.label} · Bilirubin model: ${biliStatus.mode.label}",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun WorkflowEntry(title: String, subtitle: String, status: ModelStatus, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
        border = BorderStroke(1.5.dp, Palette.Teal),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Navy)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.Body)
                val (bg, fg) = modeColors(status.mode)
                Chip(if (status.mode == ResultMode.DISABLED_NO_VALID_MODEL) "Estimate disabled · image checks only" else status.mode.label, bg, fg)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = Palette.Teal)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncCard(counts: SyncCounts, backend: BackendStatus, syncing: Boolean, onSync: () -> Unit) {
    val s = LocalStrings.current
    SectionCard(title = s.pendingSync) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Count("Pending", counts.pending)
            Count("Failed", counts.failed)
            Count("Synced", counts.synced)
            Count("Phone only", counts.localOnly)
        }
        InfoText(backend.message.ifBlank { "Server status unknown." }, if (backend.state == BackendState.REACHABLE) Palette.GreenText else Palette.Body)
        if (backend.state != BackendState.NOT_CONFIGURED && (counts.pending > 0 || counts.failed > 0)) {
            PrimaryButton(if (syncing) "Syncing…" else s.syncNow, onSync, Modifier.fillMaxWidth(), enabled = !syncing)
        }
    }
}

@Composable
private fun Count(label: String, value: Int) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = Palette.Navy)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}

fun syncLabel(state: String): Pair<String, Boolean> = when (state) {
    SyncState.SYNCED -> "Synced to server" to true
    SyncState.PENDING -> "Waiting to sync" to false
    SyncState.SYNCING -> "Syncing…" to false
    SyncState.FAILED -> "Sync failed" to false
    else -> "On this phone only" to false
}

@Composable
fun RecordRow(record: ScreeningEntity, onClick: () -> Unit) {
    val type = ScreeningType.fromWire(record.screeningType)
    val mode = ResultMode.entries.firstOrNull { it.name == record.resultMode } ?: ResultMode.DISABLED_NO_VALID_MODEL
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch).clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.Surface),
        border = BorderStroke(1.dp, Palette.Line),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(type?.displayName ?: record.screeningType, style = MaterialTheme.typography.titleSmall, color = Palette.Navy)
            Text("${formatTime(record.createdAtUtc)} · ${record.patientId}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val (bg, fg) = modeColors(mode)
                Chip(if (mode == ResultMode.DEMO_SYNTHETIC) "DEMO" else if (mode == ResultMode.DISABLED_NO_VALID_MODEL) "NO ESTIMATE" else mode.name, bg, fg)
                val (label, ok) = syncLabel(record.syncState)
                Chip(label, if (ok) Palette.TealSoft else Palette.AmberSurface, if (ok) Palette.GreenText else Palette.AmberText)
            }
        }
    }
}

@Composable
fun RecordsScreen(records: List<ScreeningEntity>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxSize()) {
        TopBar(s.allRecords, "${records.size} on this phone", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (records.isEmpty()) item { InfoText(s.noRecords) }
            items(records, key = { it.screeningId }) { RecordRow(it) { onOpen(it.screeningId) } }
        }
    }
}
