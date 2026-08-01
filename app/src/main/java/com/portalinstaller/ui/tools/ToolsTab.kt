package com.portalinstaller.ui.tools

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.data.store.HistoryEntry
import com.portalinstaller.data.store.ThemeMode
import com.portalinstaller.ui.more.HistoryTab
import com.portalinstaller.ui.more.SettingsTab
import com.portalinstaller.ui.tools.capture.CaptureTab
import com.portalinstaller.ui.tools.files.FilesTab
import com.portalinstaller.ui.tools.logcat.LogcatTab
import com.portalinstaller.ui.tools.shell.ShellTab
import com.portalinstaller.util.AppLanguage
import com.portalinstaller.viewmodel.CaptureViewModel
import com.portalinstaller.viewmodel.FileManagerViewModel
import com.portalinstaller.viewmodel.LogcatViewModel
import com.portalinstaller.viewmodel.ShellEntry

/**
 * Tab-ul „Unelte”: gazduieste sculele de depanare (shell, logcat, captura) si,
 * pentru a pastra bara de navigare la patru destinatii, mutam aici si Istoricul
 * si Setarile din vechiul tab „Mai multe”.
 *
 * Sectiunile Shell/Logcat/Captura sunt umplute in task-urile urmatoare; pana
 * atunci afiseaza un mesaj de tip „in curand”.
 */
@Composable
internal fun ToolsTab(
    context: Context,
    history: List<HistoryEntry>,
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    themeMode: ThemeMode,
    onSelectThemeMode: (ThemeMode) -> Unit,
    useDynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit,
    shellTranscript: List<ShellEntry>,
    shellIsRunning: Boolean,
    shellCommandHistory: List<String>,
    shellQuickCommands: List<String>,
    onShellRun: (String) -> Unit,
    onShellCancel: () -> Unit,
    onShellClear: () -> Unit,
    logcatViewModel: LogcatViewModel,
    captureViewModel: CaptureViewModel,
    fileManagerViewModel: FileManagerViewModel
) {
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }

    val sections = listOf(
        R.string.tools_section_shell,
        R.string.tools_section_logcat,
        R.string.tools_section_capture,
        R.string.tools_section_files,
        R.string.tools_section_history,
        R.string.tools_section_settings
    )

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selectedSection,
            edgePadding = 0.dp
        ) {
            sections.forEachIndexed { index, labelRes ->
                Tab(
                    selected = selectedSection == index,
                    onClick = { selectedSection = index },
                    text = { Text(stringResource(labelRes)) }
                )
            }
        }

        when (selectedSection) {
            0 -> ShellTab(
                context = context,
                transcript = shellTranscript,
                isRunning = shellIsRunning,
                commandHistory = shellCommandHistory,
                quickCommands = shellQuickCommands,
                onRun = onShellRun,
                onCancel = onShellCancel,
                onClear = onShellClear
            )
            1 -> LogcatTab(context = context, viewModel = logcatViewModel)
            2 -> CaptureTab(context = context, viewModel = captureViewModel)
            3 -> FilesTab(context = context, viewModel = fileManagerViewModel)
            4 -> HistoryTab(history)
            5 -> SettingsTab(
                context = context,
                selectedLanguage = selectedLanguage,
                onSelectLanguage = onSelectLanguage,
                themeMode = themeMode,
                onSelectThemeMode = onSelectThemeMode,
                useDynamicColor = useDynamicColor,
                onToggleDynamicColor = onToggleDynamicColor
            )
            else -> ComingSoonPlaceholder()
        }
    }
}

@Composable
private fun ComingSoonPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.tools_coming_soon),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
