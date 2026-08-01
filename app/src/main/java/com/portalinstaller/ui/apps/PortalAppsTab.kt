package com.portalinstaller.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.ui.common.formatFileSize
import com.portalinstaller.data.PortalAppInfo
import com.portalinstaller.viewmodel.UninstallState

@Composable
internal fun PortalAppsTab(
    packages: List<String>,
    apps: List<PortalAppInfo>,
    isLoading: Boolean,
    uninstallState: UninstallState,
    onRefresh: () -> Unit,
    onUninstall: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.portal_apps_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isLoading,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    if (isLoading) {
                        stringResource(R.string.loading)
                    } else {
                        stringResource(R.string.refresh)
                    }
                )
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (packages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.portal_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val displayApps = if (apps.isNotEmpty()) {
                apps
            } else {
                packages.map {
                    PortalAppInfo(it, it, "-", 0L, 0L, null)
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayApps, key = { it.packageName }) { app ->
                    val packageName = app.packageName
                    val uninstallInProgress = uninstallState as? UninstallState.InProgress
                    val isThisUninstalling = uninstallInProgress?.packageName == packageName
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = app.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(
                                        R.string.portal_app_details,
                                        app.versionName,
                                        formatFileSize(app.sizeBytes)
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onUninstall(packageName) },
                                enabled = !isThisUninstalling,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    if (isThisUninstalling) {
                                        stringResource(R.string.loading_ellipsis)
                                    } else {
                                        stringResource(R.string.uninstall)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
