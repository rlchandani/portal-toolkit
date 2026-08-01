package com.portalinstaller.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.viewmodel.ConnectionState

/**
 * Chip persistent de stare a conexiunii, afișat în bara de sus pe orice ecran.
 * Verde = conectat, chihlimbariu = se reconectează, neutru = deconectat.
 * Culorile sunt fixe (nu din temă) ca semantica să rămână constantă în ambele
 * teme; contrastul textului este ales manual per fundal.
 */
@Composable
internal fun ConnectionStatusChip(connectionState: ConnectionState) {
    val (dotColor, label) = when (connectionState) {
        is ConnectionState.Connected -> Color(0xFF2E7D32) to stringResource(R.string.conn_status_connected)
        is ConnectionState.Reconnecting -> {
            val text = if (connectionState.attempt > 0) {
                stringResource(R.string.conn_status_reconnecting_attempt, connectionState.attempt)
            } else {
                stringResource(R.string.conn_status_reconnecting)
            }
            Color(0xFFED6C02) to text
        }
        is ConnectionState.Connecting -> Color(0xFFED6C02) to stringResource(R.string.conn_status_reconnecting)
        else -> Color(0xFF9E9E9E) to stringResource(R.string.conn_status_disconnected)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Spacer(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
