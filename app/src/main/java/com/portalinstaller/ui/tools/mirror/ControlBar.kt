package com.portalinstaller.ui.tools.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.data.tools.PortalKey

/**
 * Rand de butoane pentru tastele hardware ale Portal-ului plus tastatura si
 * salvarea cadrului. Foloseste doar text (fara dependenta material-icons).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ControlBar(
    onKey: (PortalKey) -> Unit,
    onOpenKeyboard: () -> Unit,
    onSaveFrame: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(onClick = { onKey(PortalKey.BACK) }) {
            Text(stringResource(R.string.mirror_key_back))
        }
        TextButton(onClick = { onKey(PortalKey.HOME) }) {
            Text(stringResource(R.string.mirror_key_home))
        }
        TextButton(onClick = { onKey(PortalKey.RECENTS) }) {
            Text(stringResource(R.string.mirror_key_recents))
        }
        TextButton(onClick = onOpenKeyboard) {
            Text(stringResource(R.string.mirror_text_open))
        }
        TextButton(onClick = onSaveFrame) {
            Text(stringResource(R.string.mirror_save))
        }
    }
}
