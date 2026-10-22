package `in`.abhaynayan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.abhaynayan.app.services.CounselingSpeaker
import `in`.abhaynayan.app.ui.AbhayTheme
import `in`.abhaynayan.app.ui.AppViewModel
import `in`.abhaynayan.app.ui.HelpScreen
import `in`.abhaynayan.app.ui.HomeScreen
import `in`.abhaynayan.app.ui.LocalStrings
import `in`.abhaynayan.app.ui.Palette
import `in`.abhaynayan.app.ui.RecordScreen
import `in`.abhaynayan.app.ui.RecordsScreen
import `in`.abhaynayan.app.ui.Screen
import `in`.abhaynayan.app.ui.SettingsScreen
import `in`.abhaynayan.app.ui.Strings
import `in`.abhaynayan.app.ui.WorkflowScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AbhayTheme { AppRoot() } }
    }
}

@androidx.compose.runtime.Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val stack by vm.backStack.collectAsState()
    val settings by vm.settings.collectAsState()
    val records by vm.records.collectAsState()
    val patients by vm.patients.collectAsState()
    val counts by vm.syncCounts.collectAsState()
    val online by vm.online.collectAsState()
    val backend by vm.backendStatus.collectAsState()
    val draft by vm.draft.collectAsState()
    val message by vm.snackbar.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val dashboard by vm.serverDashboard.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val speaker = remember { CounselingSpeaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    BackHandler(enabled = stack.canGoBack) { speaker.stop(); vm.back() }

    CompositionLocalProvider(LocalStrings provides Strings(settings.language)) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = Palette.Canvas,
            modifier = Modifier.background(Palette.Navy).safeDrawingPadding(),
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                val back = { speaker.stop(); vm.back(); Unit }
                when (val screen = stack.current) {
                    Screen.Home -> HomeScreen(
                        records = records, counts = counts, online = online, backend = backend, language = settings.language,
                        syncing = syncing, hbStatus = vm.hemoglobinStatus, biliStatus = vm.bilirubinStatus,
                        onLanguage = vm::setLanguage, onStart = vm::startWorkflow,
                        onOpenRecord = { vm.navigate(Screen.Record(it)) }, onAllRecords = { vm.navigate(Screen.Records) },
                        onSync = vm::syncNow, onHelp = { vm.navigate(Screen.Help) }, onSettings = { vm.navigate(Screen.Settings) },
                    )
                    Screen.Records -> RecordsScreen(records, back) { vm.navigate(Screen.Record(it)) }
                    Screen.Settings -> SettingsScreen(settings, backend, dashboard, vm.hemoglobinStatus, vm.bilirubinStatus, vm, back)
                    Screen.Help -> HelpScreen(vm.hemoglobinStatus, vm.bilirubinStatus, back)
                    Screen.Workflow -> draft?.let { WorkflowScreen(it, patients, vm) } ?: LaunchedEffect(Unit) { vm.cancelWorkflow() }
                    is Screen.Record -> RecordScreen(screen.id, vm, speaker, back)
                }
            }
        }
    }
}
