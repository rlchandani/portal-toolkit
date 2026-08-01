package com.portalinstaller.ui.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.ui.common.SectionCardShape
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.OsUpdateState

@Composable
internal fun OsUpdateBlockCard(
    connectionState: ConnectionState,
    state: OsUpdateState,
    onRefresh: () -> Unit,
    onSetBlocked: (Boolean) -> Unit
) {
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) onRefresh()
    }

    val isConnected = connectionState is ConnectionState.Connected
    val isLoading = state is OsUpdateState.Loading
    val status = state as? OsUpdateState.Ready

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionCardShape
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.update_block_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.update_block_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = status?.status?.isBlocked == true,
                    onCheckedChange = onSetBlocked,
                    enabled = isConnected && !isLoading && status?.status?.supportedPackages?.isNotEmpty() == true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            when {
                !isConnected -> Text(
                    text = stringResource(R.string.update_block_connect_first),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.update_block_checking), style = MaterialTheme.typography.labelSmall)
                }
                state is OsUpdateState.Failed -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                status?.status?.supportedPackages?.isEmpty() == true -> Text(
                    text = stringResource(R.string.update_block_not_supported),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                status != null -> Text(
                    text = stringResource(
                        if (status.status.isBlocked) {
                            R.string.update_block_enabled
                        } else {
                            R.string.update_block_disabled
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.status.isBlocked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (isConnected && !isLoading) {
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.refresh))
                }
            }
        }
    }
}
