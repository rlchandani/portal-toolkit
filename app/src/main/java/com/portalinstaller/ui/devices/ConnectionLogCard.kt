package com.portalinstaller.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.data.ConnectionEvent
import com.portalinstaller.data.ConnectionEventKind
import com.portalinstaller.ui.common.SectionCardShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Afișează jurnalul de conexiune (heartbeat, pierderi, reconectări). Fiecare
 * intrare își obține eticheta localizată din [ConnectionEventKind], deci
 * motorul nu ține text — vezi [ConnectionEvent].
 */
@Composable
internal fun ConnectionLogCard(events: List<ConnectionEvent>) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = SectionCardShape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.conn_log_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (events.isEmpty()) {
                Text(
                    text = stringResource(R.string.conn_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            // Plafonăm afișarea la ultimele 15 intrări ca să nu umplem ecranul.
            events.take(15).forEach { event ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "•",
                        color = colorFor(event.kind),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(16.dp)
                    )
                    Text(
                        text = labelFor(event),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = timeFormat.format(Date(event.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun colorFor(kind: ConnectionEventKind): Color = when (kind) {
    ConnectionEventKind.CONNECTED_WIFI,
    ConnectionEventKind.CONNECTED_USB,
    ConnectionEventKind.RECONNECTED,
    ConnectionEventKind.HEALTHY -> Color(0xFF2E7D32)
    ConnectionEventKind.RECONNECTING -> Color(0xFFED6C02)
    ConnectionEventKind.LOST -> Color(0xFFC62828)
    ConnectionEventKind.DISCONNECTED -> Color(0xFF9E9E9E)
}

@Composable
private fun labelFor(event: ConnectionEvent): String = when (event.kind) {
    ConnectionEventKind.CONNECTED_WIFI -> stringResource(R.string.conn_event_connected_wifi)
    ConnectionEventKind.CONNECTED_USB -> stringResource(R.string.conn_event_connected_usb)
    ConnectionEventKind.HEALTHY -> stringResource(R.string.conn_event_healthy)
    ConnectionEventKind.LOST -> stringResource(R.string.conn_event_lost)
    ConnectionEventKind.RECONNECTING ->
        stringResource(R.string.conn_event_reconnecting, event.attempt ?: 1)
    ConnectionEventKind.RECONNECTED -> stringResource(R.string.conn_event_reconnected)
    ConnectionEventKind.DISCONNECTED -> stringResource(R.string.conn_event_disconnected)
}
