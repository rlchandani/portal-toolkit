package com.portalinstaller.ui.apps

import android.graphics.BitmapFactory
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.ui.common.formatFileSize
import com.portalinstaller.data.PortalAppInfo
import com.portalinstaller.viewmodel.PortalAppsSort
import com.portalinstaller.viewmodel.UninstallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun PortalAppsTab(
    packages: List<String>,
    apps: List<PortalAppInfo>,
    isLoading: Boolean,
    uninstallState: UninstallState,
    sort: PortalAppsSort,
    onSortChange: (PortalAppsSort) -> Unit,
    onRefresh: () -> Unit,
    onUninstall: (String) -> Unit,
    onLaunch: (String) -> Unit,
    onForceStop: (String) -> Unit,
    onClearData: (String) -> Unit
) {
    // Confirmarea stergerii datelor: pastram pachetul selectat pentru dialog.
    var clearDataFor by remember { mutableStateOf<String?>(null) }

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

        if (!isLoading && packages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = sort == PortalAppsSort.NAME,
                    onClick = { onSortChange(PortalAppsSort.NAME) },
                    label = { Text(stringResource(R.string.apps_sort_name)) }
                )
                FilterChip(
                    selected = sort == PortalAppsSort.SIZE,
                    onClick = { onSortChange(PortalAppsSort.SIZE) },
                    label = { Text(stringResource(R.string.apps_sort_size)) }
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
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayApps, key = { it.packageName }) { app ->
                    PortalAppCard(
                        app = app,
                        uninstallState = uninstallState,
                        onUninstall = onUninstall,
                        onLaunch = onLaunch,
                        onForceStop = onForceStop,
                        onClearData = { clearDataFor = app.packageName }
                    )
                }
            }
        }
    }

    clearDataFor?.let { packageName ->
        AlertDialog(
            onDismissRequest = { clearDataFor = null },
            title = { Text(stringResource(R.string.apps_clear_data)) },
            text = {
                Text(stringResource(R.string.apps_clear_data_confirm, packageName))
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearData(packageName)
                    clearDataFor = null
                }) {
                    Text(stringResource(R.string.apps_clear_data))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearDataFor = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun PortalAppCard(
    app: PortalAppInfo,
    uninstallState: UninstallState,
    onUninstall: (String) -> Unit,
    onLaunch: (String) -> Unit,
    onForceStop: (String) -> Unit,
    onClearData: (String) -> Unit
) {
    val packageName = app.packageName
    val uninstallInProgress = uninstallState as? UninstallState.InProgress
    val isThisUninstalling = uninstallInProgress?.packageName == packageName

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label ?: app.name,
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
            }
            Spacer(modifier = Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onLaunch(packageName) }) {
                    Text(stringResource(R.string.apps_launch))
                }
                TextButton(onClick = { onForceStop(packageName) }) {
                    Text(stringResource(R.string.apps_force_stop))
                }
                TextButton(onClick = { onClearData(packageName) }) {
                    Text(stringResource(R.string.apps_clear_data))
                }
                TextButton(
                    onClick = { onUninstall(packageName) },
                    enabled = !isThisUninstalling
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

/**
 * Afiseaza iconita aplicatiei din cache-ul local (PNG). Daca imbogatirea
 * inca nu s-a terminat (iconCachePath null sau fisier lipsa), cade inapoi
 * pe initiala numelui — la fel ca lista de aplicatii de pe telefon.
 */
@Composable
private fun AppIcon(app: PortalAppInfo) {
    // Decodarea PNG-ului atinge discul; o mutam pe IO via produceState ca sa nu
    // blocam firul principal la prima compozitie a fiecarui card.
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, app.iconCachePath) {
        value = withContext(Dispatchers.IO) {
            app.iconCachePath
                ?.let { File(it) }
                ?.takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
    }
    val loaded = bitmap
    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loaded != null) {
            androidx.compose.foundation.Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = app.label ?: app.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Text(
                text = (app.label ?: app.name).take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
