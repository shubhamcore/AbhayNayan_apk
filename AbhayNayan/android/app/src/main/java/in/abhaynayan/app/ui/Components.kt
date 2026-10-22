package `in`.abhaynayan.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.abhaynayan.app.core.ResultMode
import `in`.abhaynayan.app.core.WorkflowStage

val MinTouch = 52.dp

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 8.dp, vertical = 8.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = strings.back }) {
                Text("‹ ${strings.back}", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        } else Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, color = Color(0xFFBFDCD6), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, container: Color = Palette.Surface, border: Color = Palette.Line, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.Navy, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
fun SafetyNote(text: String) {
    SectionCard(container = Palette.AmberSurface, border = Palette.AmberLine) {
        Text("⚠ $text", style = MaterialTheme.typography.bodyMedium, color = Palette.AmberText)
    }
}

@Composable
fun InfoText(text: String, color: Color = Palette.Body) = Text(text, style = MaterialTheme.typography.bodyMedium, color = color)

@Composable
fun ErrorText(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.RedText, fontWeight = FontWeight.SemiBold)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = MinTouch), shape = RoundedCornerShape(12.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = MinTouch), shape = RoundedCornerShape(12.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}

@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = MinTouch), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.RedText)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Two actions side by side; stacks naturally when labels wrap at large font sizes. */
@Composable
fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun KeyValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Palette.Navy)
    }
}

@Composable
fun Chip(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = foreground,
        modifier = modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun StatusDot(online: Boolean) {
    val strings = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Box(Modifier.size(10.dp).background(if (online) Color(0xFF6FD39A) else Color(0xFFF2B35B), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(if (online) strings.online else strings.offline, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

fun modeColors(mode: ResultMode): Pair<Color, Color> = when (mode) {
    ResultMode.DEMO_SYNTHETIC -> Palette.PurpleSurface to Palette.Purple
    ResultMode.EXPERIMENTAL_RESEARCH_ONLY -> Palette.AmberSurface to Palette.AmberText
    ResultMode.VALIDATED_FOR_INTENDED_USE -> Palette.TealSoft to Palette.GreenText
    ResultMode.DISABLED_NO_VALID_MODEL -> Color(0xFFEDF1F2) to Palette.Navy
}

@Composable
fun ModeBanner(mode: ResultMode) {
    val (bg, fg) = modeColors(mode)
    SectionCard(container = bg, border = fg.copy(alpha = .35f)) {
        Text(mode.name, style = MaterialTheme.typography.labelSmall, color = fg)
        Text(mode.label, style = MaterialTheme.typography.titleMedium, color = fg)
        Text(mode.description, style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}

@Composable
fun StepIndicator(stage: WorkflowStage) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "Step ${stage.step} of ${WorkflowStage.COUNT}: ${stage.title}"
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            WorkflowStage.entries.forEach { s ->
                Box(Modifier.weight(1f).height(5.dp).background(if (s.step <= stage.step) Palette.Teal else Palette.Line, RoundedCornerShape(3.dp)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Step ${stage.step} of ${WorkflowStage.COUNT} · ${stage.title}", style = MaterialTheme.typography.labelMedium, color = Palette.Teal)
    }
}
