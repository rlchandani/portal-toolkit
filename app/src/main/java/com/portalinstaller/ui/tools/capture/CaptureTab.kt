package com.portalinstaller.ui.tools.capture

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.R
import com.portalinstaller.viewmodel.CaptureViewModel

/**
 * Captura de ecran a Portal-ului: buton pentru o captura, comutator „peek live”
 * (~1.5s), buton de salvare in galerie si o nota de baterie dupa peek prelungit.
 */
@Composable
internal fun CaptureTab(
    context: Context,
    viewModel: CaptureViewModel
) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val isLive by viewModel.isLive.collectAsStateWithLifecycle()
    val batteryWarning by viewModel.batteryWarning.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { viewModel.captureOnce() }) {
                Text(stringResource(R.string.capture_take))
            }
            TextButton(onClick = { viewModel.toggleLive() }) {
                Text(
                    stringResource(
                        if (isLive) R.string.capture_live_stop else R.string.capture_live_start
                    )
                )
            }
            TextButton(
                onClick = { viewModel.saveCurrent(context) },
                enabled = frame != null
            ) {
                Text(stringResource(R.string.capture_save))
            }
        }

        if (batteryWarning) {
            Text(
                text = stringResource(R.string.capture_battery_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = frame
            if (bitmap == null) {
                Text(
                    text = stringResource(R.string.capture_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}
