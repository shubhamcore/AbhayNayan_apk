package `in`.abhaynayan.app.ui

import `in`.abhaynayan.app.core.WorkflowStage
import `in`.abhaynayan.app.util.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndLoggingTest {
    @Test fun backStackKeepsHomeAsRoot() {
        val stack = BackStack().push(Screen.Settings).push(Screen.Settings).push(Screen.Help)
        assertEquals(listOf(Screen.Home, Screen.Settings, Screen.Help), stack.entries)
        assertEquals(Screen.Settings, stack.pop().current)
        assertEquals(Screen.Home, BackStack().pop().current)
        assertFalse(BackStack().canGoBack)
        assertEquals(listOf(Screen.Home, Screen.Record("scr-1")), stack.resetTo(Screen.Record("scr-1")).entries)
        assertEquals(BackStack(), stack.resetTo(Screen.Home))
    }

    @Test fun workflowStagesStepBackOneAtATime() {
        assertNull(StageFlow.previous(WorkflowStage.PROFILE))
        assertEquals(WorkflowStage.CAPTURE, StageFlow.previous(WorkflowStage.QUALITY))
        assertEquals(WorkflowStage.ANALYSIS, StageFlow.previous(WorkflowStage.RESULT))
        assertEquals(WorkflowStage.entries.map { it.step }, (1..WorkflowStage.COUNT).toList())
    }

    @Test fun redactorMasksIdentifiersAndSecrets() {
        val raw = "sync scr-1f3a9c2e-0000-4000-8000-000000000001 for ABH-K7Q2M9XP phone 9876543210 mail a.b@x.org url https://api.example.org/x?k=1 api_key=AIzaSECRET"
        val out = Redactor.redact(raw)
        listOf("1f3a9c2e", "K7Q2M9XP", "9876543210", "a.b@x.org", "api.example.org", "AIzaSECRET").forEach { assertFalse("$it leaked: $out", it in out) }
        assertTrue("<record-id>" in out && "<patient-id>" in out && "<phone>" in out && "<email>" in out && "<url>" in out)
    }
}
