package com.portalinstaller.ui.tools.shell

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.data.tools.ShellCommandClassifier
import com.portalinstaller.viewmodel.ShellEntry

/**
 * Consola de shell la distanta: transcript monospaced selectabil, chips de
 * comenzi rapide, camp de intrare cu reapel din istoric (sageti sus/jos) si
 * confirmare pentru comenzile clasificate ca distructive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellTab(
    context: Context,
    transcript: List<ShellEntry>,
    isRunning: Boolean,
    commandHistory: List<String>,
    quickCommands: List<String>,
    onRun: (String) -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var historyCursor by remember { mutableIntStateOf(-1) }
    var pendingCommand by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    fun submit(raw: String) {
        val command = raw.trim()
        if (command.isEmpty()) return
        if (ShellCommandClassifier.isDestructive(command)) {
            pendingCommand = command
        } else {
            onRun(command)
            input = TextFieldValue("")
            historyCursor = -1
        }
    }

    fun recallHistory(delta: Int) {
        if (commandHistory.isEmpty()) return
        val next = (historyCursor + delta).coerceIn(-1, commandHistory.size - 1)
        historyCursor = next
        val text = if (next < 0) "" else commandHistory[next]
        input = TextFieldValue(text, selection = TextRange(text.length))
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Comenzi rapide.
        Text(
            text = stringResource(R.string.shell_quick_commands),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickCommands.forEach { cmd ->
                AssistChip(
                    onClick = { submit(cmd) },
                    label = { Text(cmd) }
                )
            }
        }

        // Transcript.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (transcript.isEmpty()) {
                Text(
                    text = stringResource(R.string.shell_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(transcript) { entry -> TranscriptEntry(entry) }
                    }
                }
            }
        }

        // Intrare + control.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text(stringResource(R.string.shell_input_hint)) }
            )
            IconButton(onClick = { recallHistory(+1) }) {
                Text(text = "▲", style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { recallHistory(-1) }) {
                Text(text = "▼", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text(stringResource(R.string.shell_running))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.shell_cancel)) }
            } else {
                TextButton(onClick = { submit(input.text) }) {
                    Text(stringResource(R.string.shell_send))
                }
            }
            TextButton(onClick = onClear) { Text(stringResource(R.string.shell_clear)) }
        }
    }

    pendingCommand?.let { command ->
        AlertDialog(
            onDismissRequest = { pendingCommand = null },
            title = { Text(stringResource(R.string.shell_confirm_destructive_title)) },
            text = { Text(stringResource(R.string.shell_confirm_destructive_message, command)) },
            confirmButton = {
                TextButton(onClick = {
                    onRun(command)
                    input = TextFieldValue("")
                    historyCursor = -1
                    pendingCommand = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCommand = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TranscriptEntry(entry: ShellEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$ ${entry.command}",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary
        )
        if (entry.output.isNotEmpty()) {
            Text(
                text = entry.output,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (entry.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}
