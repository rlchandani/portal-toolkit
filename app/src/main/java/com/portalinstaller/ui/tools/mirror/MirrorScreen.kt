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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.R
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

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
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
                onViewSize = { viewSize = it },
                onSurface = { holder -> viewModel.onSurfaceAvailable(holder.surface) },
                onSurfaceDestroyed = { viewModel.onSurfaceDestroyed() },
                onTap = { o -> viewModel.tap(o.x, o.y, viewSize.width, viewSize.height) },
                onDrag = { start, end ->
                    viewModel.swipe(
                        start.x, start.y, end.x, end.y,
                        viewSize.width, viewSize.height, 200
                    )
                },
                onLongPress = { o ->
                    viewModel.longPress(o.x, o.y, viewSize.width, viewSize.height)
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
private fun MirrorSurface(
    onViewSize: (IntSize) -> Unit,
    onSurface: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onTap: (Offset) -> Unit,
    onDrag: (Offset, Offset) -> Unit,
    onLongPress: (Offset) -> Unit
) {
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragCurrent by remember { mutableStateOf(Offset.Zero) }
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                onViewSize(IntSize(size.width, size.height))
                detectTapGestures(
                    onTap = { onTap(it) },
                    onLongPress = { onLongPress(it) }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragStart = it
                        dragCurrent = it
                    },
                    onDragEnd = { onDrag(dragStart, dragCurrent) }
                ) { change, _ ->
                    dragCurrent = change.position
                    change.consume()
                }
            },
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder)
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {
                        onViewSize(IntSize(width, height))
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceDestroyed()
                })
            }
        }
    )
}
