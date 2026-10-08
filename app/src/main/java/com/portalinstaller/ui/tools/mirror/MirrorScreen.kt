package com.portalinstaller.ui.tools.mirror

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.R
import com.portalinstaller.data.tools.MirrorGeometry
import com.portalinstaller.viewmodel.MirrorState
import com.portalinstaller.viewmodel.MirrorViewModel

/**
 * Ecranul de oglindire: gazduieste un SurfaceView (via AndroidView), preda
 * Surface-ul catre VM, detecteaza tap/drag/long-press si le trimite cu
 * dimensiunea masurata a view-ului. Arata un spinner pana la primul cadru; pe
 * Unsupported arata mesaj + buton de revenire la captura statica.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MirrorScreen(
    context: Context,
    viewModel: MirrorViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val firstFrame by viewModel.firstFrameRendered.collectAsStateWithLifecycle()
    val batteryWarning by viewModel.batteryWarning.collectAsStateWithLifecycle()
    val deviceGeometry by viewModel.deviceGeometry.collectAsStateWithLifecycle()

    var showKeyboard by remember { mutableStateOf(false) }
    var textToSend by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        if (batteryWarning) {
            Text(
                text = stringResource(R.string.mirror_battery_note),
                modifier = Modifier.padding(8.dp)
            )
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            MirrorSurface(
                deviceGeometry = deviceGeometry,
                onSurface = { holder -> viewModel.onSurfaceAvailable(holder.surface) },
                onSurfaceDestroyed = { viewModel.onSurfaceDestroyed() },
                onTap = { o, size -> viewModel.tap(o.x, o.y, size.width, size.height) },
                onDrag = { start, end, size ->
                    viewModel.swipe(
                        start.x, start.y, end.x, end.y,
                        size.width, size.height, 200
                    )
                },
                onLongPress = { o, size ->
                    viewModel.longPress(o.x, o.y, size.width, size.height)
                }
            )
            when (val s = state) {
                is MirrorState.Unsupported -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message)
                        TextButton(onClick = { viewModel.saveCurrentFrame(context) }) {
                            Text(stringResource(R.string.mirror_fallback_peek))
                        }
                    }
                }
                is MirrorState.Error -> Text(s.message)
                is MirrorState.NotConnected ->
                    Text(stringResource(R.string.mirror_not_connected))
                else -> {
                    if (!firstFrame) CircularProgressIndicator()
                }
            }
        }

        ControlBar(
            onKey = { key -> viewModel.sendKey(key) },
            onOpenKeyboard = { showKeyboard = true },
            onSaveFrame = { viewModel.saveCurrentFrame(context) }
        )
    }

    if (showKeyboard) {
        ModalBottomSheet(onDismissRequest = { showKeyboard = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(
                    value = textToSend,
                    onValueChange = { textToSend = it },
                    label = { Text(stringResource(R.string.mirror_text_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        viewModel.sendText(textToSend)
                        textToSend = ""
                        showKeyboard = false
                    }) {
                        Text(stringResource(R.string.mirror_text_send))
                    }
                }
            }
        }
    }
}

/**
 * SurfaceView-ul propriu-zis plus detectia gesturilor. Masuram dimensiunea
 * view-ului pentru maparea coordonatelor din VM. Drag trimite o singura comanda
 * swipe la sfarsitul gestului pentru a evita supraincarcarea ADB.
 */
@Composable
internal fun MirrorSurface(
    deviceGeometry: MirrorGeometry?,
    onSurface: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTap: (Offset, IntSize) -> Unit,
    onDrag: (Offset, Offset, IntSize) -> Unit,
    onLongPress: (Offset, IntSize) -> Unit,
    modifier: Modifier = Modifier
) {
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val currentOnSurface by rememberUpdatedState(onSurface)
    val currentOnSurfaceDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    // Keep a surface available to bootstrap the size query before dimensions are known.
    val geometry = deviceGeometry ?: remember { MirrorGeometry(1, 1) }
    AndroidView(
        modifier = modifier
            .layout { measurable, constraints ->
                val (width, height) = geometry.fitWithin(constraints.maxWidth, constraints.maxHeight)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .onSizeChanged { surfaceSize = it }
            .pointerInput(deviceGeometry, surfaceSize) {
                detectTapGestures(
                    onTap = { currentOnTap(it, size) },
                    onLongPress = { currentOnLongPress(it, size) }
                )
            }
            .pointerInput(deviceGeometry, surfaceSize) {
                var dragStart = Offset.Zero
                var dragCurrent = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        dragStart = it
                        dragCurrent = it
                    },
                    onDragEnd = { currentOnDrag(dragStart, dragCurrent, size) }
                ) { change, _ ->
                    dragCurrent = change.position
                    change.consume()
                }
            },
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = currentOnSurface(holder)
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = currentOnSurfaceDestroyed()
                })
            }
        }
    )
}
