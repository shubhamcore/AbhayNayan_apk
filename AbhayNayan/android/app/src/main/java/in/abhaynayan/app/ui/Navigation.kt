package `in`.abhaynayan.app.ui

import `in`.abhaynayan.app.core.WorkflowStage

sealed interface Screen {
    data object Home : Screen
    data object Records : Screen
    data object Settings : Screen
    data object Help : Screen
    data object Workflow : Screen
    data class Record(val id: String) : Screen
}

/** Immutable back stack. Home is always the root and is never popped. */
data class BackStack(val entries: List<Screen> = listOf(Screen.Home)) {
    val current: Screen get() = entries.last()
    val canGoBack: Boolean get() = entries.size > 1

    fun push(screen: Screen): BackStack = if (current == screen) this else copy(entries = entries + screen)
    fun pop(): BackStack = if (canGoBack) copy(entries = entries.dropLast(1)) else this
    /** Replaces everything above Home, e.g. after a record is saved. */
    fun resetTo(screen: Screen): BackStack = if (screen == Screen.Home) BackStack() else BackStack(listOf(Screen.Home, screen))
}

/** Stage transitions inside a workflow. Moving back never skips the quality gate when moving forward again. */
object StageFlow {
    fun previous(stage: WorkflowStage): WorkflowStage? = when (stage) {
        WorkflowStage.PROFILE -> null
        WorkflowStage.DETAILS -> WorkflowStage.PROFILE
        WorkflowStage.PREPARE -> WorkflowStage.DETAILS
        WorkflowStage.CAPTURE -> WorkflowStage.PREPARE
        WorkflowStage.QUALITY -> WorkflowStage.CAPTURE
        WorkflowStage.ANALYSIS -> WorkflowStage.QUALITY
        WorkflowStage.RESULT -> WorkflowStage.ANALYSIS
    }
}
