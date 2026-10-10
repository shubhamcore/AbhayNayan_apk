package `in`.abhaynayan.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.abhaynayan.app.core.CaptureAssessment
import `in`.abhaynayan.app.core.DangerSign
import `in`.abhaynayan.app.core.DemoInference
import `in`.abhaynayan.app.core.MetadataForm
import `in`.abhaynayan.app.core.ModelRegistry
import `in`.abhaynayan.app.core.ModelStatus
import `in`.abhaynayan.app.core.PregnancyStatus
import `in`.abhaynayan.app.core.ReferralRules
import `in`.abhaynayan.app.core.RgbImage
import `in`.abhaynayan.app.core.ScreeningType
import `in`.abhaynayan.app.core.WorkflowStage
import `in`.abhaynayan.app.data.PatientEntity
import `in`.abhaynayan.app.data.SubjectKind
import java.util.Locale

@Composable
fun WorkflowScreen(
    draft: WorkflowDraft,
    patients: List<PatientEntity>,
    vm: AppViewModel,
) {
    val s = LocalStrings.current
    val title = if (draft.type == ScreeningType.MATERNAL_ANEMIA) s.maternalTitle else s.neonatalTitle
    val patient = patients.firstOrNull { it.patientId == draft.patientId }
    Column(Modifier.fillMaxSize()) {
        TopBar(title, draft.stage.title, onBack = { vm.back() }) {
            SecondaryButtonOnDark(s.cancel) { vm.cancelWorkflow() }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { StepIndicator(draft.stage) }
            when (draft.stage) {
                WorkflowStage.PROFILE -> item { ProfileStage(draft, patients, vm) }
                WorkflowStage.DETAILS -> item { DetailsStage(draft, vm) }
                WorkflowStage.PREPARE -> item { PrepareStage(draft.type) { vm.setStage(WorkflowStage.CAPTURE) } }
                WorkflowStage.CAPTURE -> item { CaptureStage(draft, patient, vm) }
                WorkflowStage.QUALITY -> item { QualityStage(draft, vm) }
                WorkflowStage.ANALYSIS -> item { AnalysisStage(draft, patient, vm) }
                WorkflowStage.RESULT -> item {
                    val preview = draft.preview
                    if (preview != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ResultDetails(preview, draft.modelStatus)
                            InfoText("Not saved yet. Saving creates the screening report (PDF), counselling, and referral.")
                            ActionRow {
                                SecondaryButton(s.back, { vm.setStage(WorkflowStage.ANALYSIS) }, Modifier.weight(1f))
                                PrimaryButton(if (draft.saving) "Saving…" else s.save, { vm.saveResult() }, Modifier.weight(1f), enabled = !draft.saving)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondaryButtonOnDark(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ProfileStage(draft: WorkflowDraft, patients: List<PatientEntity>, vm: AppViewModel) {
    val s = LocalStrings.current
    val kind = if (draft.type == ScreeningType.MATERNAL_ANEMIA) SubjectKind.MATERNAL else SubjectKind.NEWBORN
    val matching = patients.filter { it.subjectKind == kind || it.subjectKind == SubjectKind.UNSPECIFIED }
    var nickname by rememberSaveable { mutableStateOf("") }
    var synthetic by rememberSaveable { mutableStateOf(true) }
    var consent by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (kind == SubjectKind.MATERNAL) "Who is being screened?" else "Which newborn is being screened?", style = MaterialTheme.typography.titleLarge, color = Palette.Navy)
        SectionCard(title = "Profiles on this phone") {
            if (matching.isEmpty()) InfoText("No matching profiles yet. Create one below.")
            matching.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = MinTouch)
                        .selectable(selected = draft.patientId == p.patientId, role = Role.RadioButton) { vm.selectPatient(p.patientId) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = draft.patientId == p.patientId, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(p.displayName.ifBlank { "(no nickname)" }, style = MaterialTheme.typography.bodyLarge, color = Palette.Navy)
                        Text("${p.patientId} · ${if (p.isSyntheticDemo) "synthetic demo profile" else "real/local profile"}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    }
                }
            }
        }
        SectionCard(title = "Create a profile") {
            InfoText("Only a pseudonymous ID is created. A nickname is optional, stays on this phone, and is never synced or exported.")
            OutlinedTextField(nickname, { nickname = it.take(40) }, label = { Text("Nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().heightIn(min = MinTouch).toggleable(synthetic, role = Role.Switch) { synthetic = it }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Synthetic demo profile", style = MaterialTheme.typography.bodyLarge, color = Palette.Navy)
                    Text("Demo profiles can show fixed synthetic values. Real profiles get image checks only — no estimate.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
                Switch(checked = synthetic, onCheckedChange = null)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = MinTouch).toggleable(consent, role = Role.Checkbox) { consent = it }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = consent, onCheckedChange = null)
                Text("Consent was given to store this profile on this phone.", style = MaterialTheme.typography.bodyMedium, color = Palette.Body, modifier = Modifier.padding(start = 8.dp))
            }
            SecondaryButton("Create profile", { vm.createPatient(nickname, synthetic, consent); nickname = ""; consent = false }, Modifier.fillMaxWidth(), enabled = consent)
        }
        PrimaryButton(s.continueLabel, { vm.profileContinue() }, Modifier.fillMaxWidth(), enabled = draft.patientId != null)
    }
}

@Composable
private fun DetailsStage(draft: WorkflowDraft, vm: AppViewModel) {
    val s = LocalStrings.current
    val form = draft.form
    val errors = draft.formErrors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (draft.type == ScreeningType.MATERNAL_ANEMIA) {
            Text("Pregnancy details", style = MaterialTheme.typography.titleLarge, color = Palette.Navy)
            SectionCard(title = "Is she currently pregnant?") {
                listOf(PregnancyStatus.PREGNANT to "Pregnant", PregnancyStatus.NOT_PREGNANT to "Not pregnant", PregnancyStatus.UNKNOWN to "Not known / not recorded").forEach { (value, label) ->
                    RadioRow(label, form.pregnancy == value) { vm.updateForm(form.copy(pregnancy = value, gestationalWeek = if (value == PregnancyStatus.PREGNANT) form.gestationalWeek else "")) }
                }
                if (form.pregnancy == PregnancyStatus.PREGNANT) {
                    NumberField("Gestational week (optional)", form.gestationalWeek, errors["gestationalWeek"]) { vm.updateForm(form.copy(gestationalWeek = it)) }
                }
            }
        } else {
            Text("Newborn details", style = MaterialTheme.typography.titleLarge, color = Palette.Navy)
            SectionCard(title = "Age and birth") {
                NumberField("Age in hours (required)", form.infantAgeHours, errors["infantAgeHours"]) { vm.updateForm(form.copy(infantAgeHours = it)) }
                InfoText("Example: 2 days and 6 hours = 54 hours.", Palette.Muted)
                NumberField("Gestational age at birth, weeks (optional)", form.gestationalAgeAtBirthWeeks, errors["gestationalAgeAtBirthWeeks"]) { vm.updateForm(form.copy(gestationalAgeAtBirthWeeks = it)) }
            }
            SectionCard(title = "Is yellow colour visible on the skin or eyes?") {
                RadioRow("Yes", form.visibleJaundice == true) { vm.updateForm(form.copy(visibleJaundice = true)) }
                RadioRow("No", form.visibleJaundice == false) { vm.updateForm(form.copy(visibleJaundice = false)) }
                errors["visibleJaundice"]?.let { ErrorText(it) }
            }
        }
        SectionCard(title = s.dangerSigns) {
            DangerSign.forType(draft.type).forEach { sign ->
                val checked = sign in form.dangerSigns
                Row(Modifier.fillMaxWidth().heightIn(min = MinTouch).toggleable(checked, role = Role.Checkbox) {
                    vm.updateForm(form.copy(dangerSigns = if (it) form.dangerSigns + sign else form.dangerSigns - sign))
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(sign.label, style = MaterialTheme.typography.bodyLarge, color = Palette.Navy, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        val age = form.infantAgeHours.toIntOrNull()
        val urgentNow = form.dangerSigns.any { it.type == draft.type } ||
            (draft.type == ScreeningType.NEONATAL_JAUNDICE && form.visibleJaundice == true && age != null && age < 24)
        if (urgentNow) {
            SectionCard(container = Palette.RedSurface, border = Palette.RedText.copy(alpha = .4f)) {
                Text("URGENT REFERRAL", style = MaterialTheme.typography.labelSmall, color = Palette.RedText)
                Text("Do not wait for the app. Arrange urgent referral under local protocol now. You may still record the screening.", style = MaterialTheme.typography.bodyLarge, color = Palette.RedText)
                Text(ReferralRules.REVIEW_STATUS, style = MaterialTheme.typography.bodySmall, color = Palette.RedText)
            }
        }
        if (errors.isNotEmpty()) ErrorText("Please correct the highlighted fields.")
        PrimaryButton(s.continueLabel, { vm.submitDetails() }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = MinTouch).selectable(selected, role = Role.RadioButton, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.Navy, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun NumberField(label: String, value: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrepareStage(type: ScreeningType, onContinue: () -> Unit) {
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Prepare for the photo", style = MaterialTheme.typography.titleLarge, color = Palette.Navy)
        SectionCard(title = if (type == ScreeningType.MATERNAL_ANEMIA) "Lower eyelid (palpebral conjunctiva)" else "White of the eye (sclera)") {
            val steps = if (type == ScreeningType.MATERNAL_ANEMIA) listOf(
                "Wash hands. Explain the photo and ask permission.",
                "Use even daylight indoors or shade. Avoid direct sunlight and coloured lights.",
                "Gently pull down the lower eyelid so the inner red surface is visible.",
                "Hold the phone about 10–15 cm away and keep the inner eyelid inside the yellow box.",
            ) else listOf(
                "Wash hands. Explain the photo to the parent and ask permission.",
                "Use even daylight indoors or shade. Avoid direct sunlight; never shine a bright light into the baby's eyes for long.",
                "Take the photo only when the baby's eyes are naturally open; do not force the eyelids.",
                "Hold the phone about 15–20 cm away with the white of the eye inside the yellow box.",
            )
            steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyLarge, color = Palette.Body) }
        }
        SectionCard(title = "What the app does with the photo") {
            InfoText("The photo stays in memory on this phone, is checked for blur, light, glare and movement, and is then discarded. It is never saved or uploaded.")
            InfoText("A flash pair (one photo without and one with the phone light) is taken only when this phone can hold exposure constant. Otherwise a single photo is used.")
            InfoText("No face or eye detector is installed: you confirm the target area yourself.")
        }
        PrimaryButton(s.continueLabel, onContinue, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CaptureStage(draft: WorkflowDraft, patient: PatientEntity?, vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.onImagePicked(uri) }
    val target = if (draft.type == ScreeningType.MATERNAL_ANEMIA) "lower eyelid" else "white of the eye"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CameraPanel(target, onFrames = { vm.onFramesCaptured(it, synthetic = false) }, onError = { vm.onCaptureFailed(it) })
        draft.captureError?.let { ErrorText(it) }
        SecondaryButton("Choose a photo from the phone (JPEG/PNG/WebP)", { picker.launch("image/*") }, Modifier.fillMaxWidth())
        if (patient?.isSyntheticDemo == true) {
            SecondaryButton("Use the synthetic colour chart (demo, no camera)", { vm.useSyntheticChart() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QualityStage(draft: WorkflowDraft, vm: AppViewModel) {
    val s = LocalStrings.current
    val a = draft.assessment
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Image checks", style = MaterialTheme.typography.titleLarge, color = Palette.Navy)
        if (draft.assessing || a == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp))
                InfoText("Checking the photo on this phone…")
            }
            return@Column
        }
        val passed = a.quality.passed
        SectionCard(
            container = if (passed) Palette.TealSoft else Palette.RedSurface,
            border = if (passed) Palette.Teal.copy(alpha = .4f) else Palette.RedText.copy(alpha = .4f),
        ) {
            Text(if (passed) "Checks passed" else "Please retake", style = MaterialTheme.typography.titleMedium, color = if (passed) Palette.GreenText else Palette.RedText)
            if (a.quality.issues.isEmpty()) InfoText("Light, sharpness, glare and framing look usable.")
            a.quality.issues.forEach { issue ->
                Text("• ${issue.message}", style = MaterialTheme.typography.bodyLarge, color = if (issue.blocking) Palette.RedText else Palette.AmberText)
            }
            if (a.differential?.motionCorrected == true) {
                InfoText("Small hand movement between the two photos was corrected automatically.", Palette.GreenText)
            }
            Text("These are photo-quality checks only. Passing them does not mean any estimate would be accurate.", style = MaterialTheme.typography.bodySmall, color = Palette.Body)
        }
        if (passed && !a.syntheticChart) {
            Row(Modifier.fillMaxWidth().heightIn(min = MinTouch).toggleable(draft.regionConfirmed, role = Role.Checkbox) { vm.confirmRegion(it) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = draft.regionConfirmed, onCheckedChange = null)
                Text("I confirm the ${if (draft.type == ScreeningType.MATERNAL_ANEMIA) "inner lower eyelid" else "white of the eye"} is inside the box in this photo.",
                    style = MaterialTheme.typography.bodyLarge, color = Palette.Navy, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (a.syntheticChart) InfoText("Synthetic colour chart: not an eye or a person. Its guide region is used automatically.")
        ImagePreviewRow(a)
        TechnicalDetails(a)
        ActionRow {
            SecondaryButton(s.retake, { vm.retake() }, Modifier.weight(1f))
            PrimaryButton(s.continueLabel, { vm.setStage(WorkflowStage.ANALYSIS) }, Modifier.weight(1f), enabled = passed && draft.regionConfirmed)
        }
    }
}

@Composable
private fun ImagePreviewRow(a: CaptureAssessment) {
    val optical = a.optical ?: return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PixelTile("Photo", optical.ambient)
        optical.flash?.let { PixelTile("With flash", it) }
        if (optical.differentialApplied) PixelTile("Flash − ambient", optical.compensated)
        PixelTile("Target area", optical.roi)
    }
}

@Composable
private fun PixelTile(label: String, image: RgbImage) {
    val bitmap = remember(image) { Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888) }
    DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
    Column(Modifier.width(120.dp)) {
        Image(bitmap.asImageBitmap(), contentDescription = "$label preview, held in memory only", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(90.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}

@Composable
private fun TechnicalDetails(a: CaptureAssessment) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("Technical details (for supervisors)", style = MaterialTheme.typography.titleSmall, color = Palette.Navy, modifier = Modifier.weight(1f))
            Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = Palette.Teal)
        }
        if (!open) return@SectionCard
        val q = a.quality
        KeyValue("Source", a.frames.source)
        KeyValue("Brightness (mean luma)", "%.0f / 255".format(Locale.ROOT, q.meanLuma))
        KeyValue("Sharpness (Laplacian variance)", "%.1f (minimum 18)".format(Locale.ROOT, q.sharpness))
        KeyValue("Clipped pixels in target", "%.1f%%".format(Locale.ROOT, q.clippedFraction * 100))
        a.frames.notes.forEach { InfoText("• $it", Palette.Muted) }
        val d = a.differential
        if (d == null) KeyValue("Flash comparison", "Not captured (single photo).")
        else {
            KeyValue("Flash comparison", if (d.accepted) "Accepted" else "Rejected")
            d.rejectionReasons.forEach { InfoText("• $it", Palette.AmberText) }
            KeyValue("Frame interval", d.intervalMs?.let { "$it ms (nominal target 100 ms; not guaranteed)" } ?: "not reported")
            KeyValue("Exposure / ISO ratio", "${d.exposureRatio?.let { "%.3f".format(Locale.ROOT, it) } ?: "n/a"} / ${d.isoRatio?.let { "%.3f".format(Locale.ROOT, it) } ?: "n/a"}")
            KeyValue("Alignment shift / residual", "(${d.shiftX}, ${d.shiftY}) px / %.2f".format(Locale.ROOT, d.alignmentResidual))
            KeyValue("Valid differential pixels", "%.0f%%".format(Locale.ROOT, d.validPixelFraction * 100))
            d.erythemaIndexMean?.let { KeyValue("Exploratory erythema index EI = ln(ΔR+ε) − ln(ΔG+ε)", "mean %.3f · median %.3f (research feature, not a biomarker)".format(Locale.ROOT, it, d.erythemaIndexMedian ?: Double.NaN)) }
        }
        a.optical?.features?.let { f ->
            KeyValue("Target RGB mean", "%.0f / %.0f / %.0f".format(Locale.ROOT, f.rMean, f.gMean, f.bMean))
            KeyValue("CIE L*a*b*", "%.1f / %.1f / %.1f".format(Locale.ROOT, f.labLStar, f.labAStar, f.labBStar))
        }
        InfoText("Engineering values only. They are not calibrated and are not converted into hemoglobin or bilirubin.", Palette.Muted)
    }
}

@Composable
private fun AnalysisStage(draft: WorkflowDraft, patient: PatientEntity?, vm: AppViewModel) {
    val s = LocalStrings.current
    val status: ModelStatus = if (draft.type == ScreeningType.MATERNAL_ANEMIA) vm.hemoglobinStatus else vm.bilirubinStatus
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Analysis", style = MaterialTheme.typography.titleLarge, color = Palette.Navy)
        SectionCard(title = "${status.biomarker.displayName} model status") {
            Text(status.mode.label, style = MaterialTheme.typography.titleMedium, color = Palette.Navy)
            status.reasons.forEach { InfoText("• $it") }
            ModelRegistry.inspectedCandidates.filter { it.biomarker == status.biomarker }.forEach { c ->
                Text(c.name, style = MaterialTheme.typography.titleSmall, color = Palette.Navy)
                InfoText(c.whyNotDeployed, Palette.Muted)
            }
        }
        if (patient?.isSyntheticDemo == true) {
            SectionCard(title = "Synthetic demo case (optional)", container = Palette.PurpleSurface, border = Palette.Purple.copy(alpha = .35f)) {
                InfoText("Demo profiles may attach a FIXED synthetic value for training and demonstrations. It is NOT calculated from the photo.", Palette.Purple)
                RadioRow("No demo value — record image checks only", draft.demoCaseId == null) { vm.selectDemoCase(null) }
                DemoInference.cases(draft.type).forEach { c ->
                    RadioRow("${c.label}: ${"%.1f".format(Locale.ROOT, c.estimate)} ${c.unit} (${c.demoTag})", draft.demoCaseId == c.id) { vm.selectDemoCase(c.id) }
                }
            }
        } else {
            InfoText("This is a real/local profile, so synthetic demo values are not available. The record will show that no estimate was produced.")
        }
        draft.analysisError?.let { ErrorText(it) }
        PrimaryButton(if (draft.analysisError == null) "Show result" else s.retry, { vm.runAnalysis() }, Modifier.fillMaxWidth())
    }
}
