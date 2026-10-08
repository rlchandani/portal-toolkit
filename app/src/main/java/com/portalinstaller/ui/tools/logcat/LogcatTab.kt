package com.portalinstaller.ui.tools.logcat

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.R
import com.portalinstaller.data.tools.LogLine
import com.portalinstaller.ui.common.SectionCard
import com.portalinstaller.ui.tools.shell.ToolTabLayout
import com.portalinstaller.viewmodel.LogcatViewModel

private val LEVEL_COLORS = mapOf(
    'E' to Color(0xFFC62828),
    'W' to Color(0xFFED6C02),
    'I' to Color(0xFF2E7D32),
    'D' to Color(0xFF9E9E9E)
)

/**
 * Vizualizator logcat live: chips de nivel, filtru text client-side, comutator
 * „doar erori”, export si banner de crash cu salt la linia „FATAL EXCEPTION”.
 * Auto-scroll la coada cat timp streamingul e activ, cu pauza cand utilizatorul
 * deruleaza in sus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogcatTab(
    context: Context,
    viewModel: LogcatViewModel
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        // The activity-scoped ViewModel outlives this tab. Stop its stream when
        // the UI stops or leaves composition; returning still requires Start.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.stop()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stop()
        }
    }

    val buffer by viewModel.buffer.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val levelFilter by viewModel.levelFilter.collectAsStateWithLifecycle()
    val tagFilter by viewModel.tagFilter.collectAsStateWithLifecycle()
    val errorsOnly by viewModel.errorsOnly.collectAsStateWithLifecycle()
    val crashIndex by viewModel.crashLineIndex.collectAsStateWithLifecycle()

    // Recalculam lista vizibila cand se schimba bufferul sau filtrele.
    val visible = remember(buffer, levelFilter, tagFilter) { viewModel.visibleLines() }

    val listState = rememberLazyListState()
    LaunchedEffect(visible.size, isStreaming) {
        if (isStreaming && visible.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            // Auto-scroll doar daca utilizatorul e deja aproape de coada.
            if (lastVisible >= visible.size - 3) {
                listState.animateScrollToItem(visible.size - 1)
            }
        }
    }

    ToolTabLayout { transcriptModifier ->
        LogcatControls(
            isStreaming = isStreaming,
            errorsOnly = errorsOnly,
            onStart = {
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    viewModel.start()
                }
            },
            onStop = { viewModel.stop() },
            onClear = { viewModel.clear() },
            onExport = { exportLogcat(context, visible) },
            onToggleErrorsOnly = { viewModel.setErrorsOnly(it) }
        )

        LevelChips(
            selected = levelFilter,
            onSelect = { viewModel.setLevelFilter(it) }
        )

        OutlinedTextField(
            value = tagFilter,
            onValueChange = { viewModel.setTagFilter(it) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.logcat_filter_hint)) }
        )

        crashIndex?.let { index ->
            CrashBanner(
                index = index,
                listState = listState,
                visibleSize = visible.size,
                onDismiss = { viewModel.consumeCrashSignal() }
            )
        }

        Box(modifier = transcriptModifier) {
            if (visible.isEmpty()) {
                Text(
                    text = stringResource(R.string.logcat_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(visible) { _, line -> LogRow(line) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogcatControls(
    isStreaming: Boolean,
    errorsOnly: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onToggleErrorsOnly: (Boolean) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (isStreaming) {
            TextButton(onClick = onStop, modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(stringResource(R.string.logcat_stop))
            }
        } else {
            TextButton(onClick = onStart, modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(stringResource(R.string.logcat_start))
            }
        }
        TextButton(onClick = onClear, modifier = Modifier.align(Alignment.CenterVertically)) {
            Text(stringResource(R.string.logcat_clear))
        }
        TextButton(onClick = onExport, modifier = Modifier.align(Alignment.CenterVertically)) {
            Text(stringResource(R.string.logcat_export))
        }
        Row(
            modifier = Modifier.align(Alignment.CenterVertically),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.logcat_errors_only),
                style = MaterialTheme.typography.labelMedium
            )
            Switch(checked = errorsOnly, onCheckedChange = onToggleErrorsOnly)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelChips(
    selected: Char?,
    onSelect: (Char?) -> Unit
) {
    val options: List<Pair<Char?, Int>> = listOf(
        null to R.string.logcat_level_all,
        'E' to R.string.logcat_level_e,
        'W' to R.string.logcat_level_w,
        'I' to R.string.logcat_level_i,
        'D' to R.string.logcat_level_d
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (level, labelRes) ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(stringResource(labelRes)) }
            )
        }
    }
}

@Composable
private fun CrashBanner(
    index: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    visibleSize: Int,
    onDismiss: () -> Unit
) {
    // Padding interior de 16dp este aplicat de SectionCard; pastram doar marginea verticala
    SectionCard(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.logcat_crash_detected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            val target = index.coerceIn(0, (visibleSize - 1).coerceAtLeast(0))
            var requestJump by remember { mutableStateOf(false) }
            LaunchedEffect(requestJump) {
                if (requestJump) {
                    if (visibleSize > 0) listState.animateScrollToItem(target)
                    requestJump = false
                    onDismiss()
                }
            }
            TextButton(onClick = { requestJump = true }) {
                Text(stringResource(R.string.logcat_jump))
            }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color = LEVEL_COLORS[line.level] ?: MaterialTheme.colorScheme.onSurface
    Text(
        text = if (line.tag.isEmpty()) line.message else "${line.level}/${line.tag}: ${line.message}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
    )
}

private fun exportLogcat(context: Context, lines: List<LogLine>) {
    val text = lines.joinToString("\n") { it.raw }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.logcat_export_subject))
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, context.getString(R.string.logcat_export))
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    ContextCompat.startActivity(context, chooser, null)
}
