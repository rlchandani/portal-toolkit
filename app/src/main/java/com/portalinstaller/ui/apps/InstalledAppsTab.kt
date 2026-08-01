package com.portalinstaller.ui.apps

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.ui.common.formatFileSize
import com.portalinstaller.data.ContentUriHelper
import com.portalinstaller.data.InstalledApp
import com.portalinstaller.data.InstallPolicy
import com.portalinstaller.data.PortalDeviceConfig
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.InstallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cuvinte-cheie care semnaleaza split APK-uri dependente de dispozitivul sursa.
 */
private val SPLIT_APK_RISK_KEYWORDS = listOf(
    "config.arm64_v8a",
    "config.armeabi_v7a",
    "config.x86",
    "config.x86_64",
    "config.hdpi",
    "config.xhdpi",
    "config.xxhdpi",
    "config.xxxhdpi"
)

/**
 * Pragul de marime peste care cerem confirmare explicita inainte de instalare.
 */
private const val LARGE_APP_SIZE_THRESHOLD_BYTES = 50L * 1024 * 1024 // 50 MB

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstalledAppsTab(
    apps: List<InstalledApp>,
    connectionState: ConnectionState,
    installState: InstallState,
    externalInstallBusy: Boolean,
    context: Context,
    selectedUri: Uri?,
    onPickFile: () -> Unit,
    onInstall: (InstalledApp) -> Unit,
    onInstallCustomApk: (Uri) -> Unit
) {
    val installBusy = installState is InstallState.Installing || externalInstallBusy
    var searchQuery by remember { mutableStateOf("") }
    var pendingConfirmation by remember { mutableStateOf<InstalledApp?>(null) }
    var sortBySize by remember { mutableStateOf(false) }
    val sortLabel = if (sortBySize) {
        stringResource(R.string.sort_size_compact)
    } else {
        stringResource(R.string.sort_name_compact)
    }

    val filteredApps = remember(apps, searchQuery, sortBySize) {
        val base = if (searchQuery.isBlank()) {
            apps
        } else {
            apps.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
        if (sortBySize) {
            base.sortedByDescending { it.apkSize }
        } else {
            base.sortedBy { it.name.lowercase() }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_app_label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        TextButton(
                            onClick = { searchQuery = "" },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("X")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { sortBySize = !sortBySize },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                ) {
                    Text(sortLabel)
                }
                OutlinedButton(
                    onClick = onPickFile,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .width(104.dp)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.choose_apk_compact))
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (selectedUri != null) {
            val fileName = remember(selectedUri) {
                ContentUriHelper.getDisplayName(context, selectedUri)
                    ?: context.getString(R.string.unknown_apk_file)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.selected_file_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = { onInstallCustomApk(selectedUri) },
                        enabled = connectionState is ConnectionState.Connected &&
                            !installBusy,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (installBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.install_on_portal))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        if (apps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.loading_installed_apps),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_apps_found, searchQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    InstalledAppItem(
                        app = app,
                        context = context,
                        isConnected = connectionState is ConnectionState.Connected,
                        isInstalling = installBusy,
                        onInstall = {
                            val totalSize = app.apkSize +
                                (app.splitSourceDirs?.sumOf { java.io.File(it).length() } ?: 0L)
                            if (totalSize > LARGE_APP_SIZE_THRESHOLD_BYTES) {
                                pendingConfirmation = app
                            } else {
                                onInstall(app)
                            }
                        }
                    )
                }
            }
        }
    }

    pendingConfirmation?.let { app ->
        val totalSize = app.apkSize + (app.splitSourceDirs?.sumOf { java.io.File(it).length() } ?: 0L)
        val hasRiskySplits = (
            app.splitSourceDirs?.any { path ->
                SPLIT_APK_RISK_KEYWORDS.any { keyword -> path.contains(keyword, ignoreCase = true) }
            } == true
        )
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(stringResource(R.string.confirm_install_title)) },
            text = {
                val warning = if (hasRiskySplits) {
                    stringResource(R.string.confirm_install_split_warning)
                } else {
                    ""
                }
                Text(
                    stringResource(
                        R.string.confirm_install_message,
                        app.name,
                        formatFileSize(totalSize)
                    ) + warning
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onInstall(app)
                        pendingConfirmation = null
                    },
                    enabled = !installBusy
                ) {
                    Text(stringResource(R.string.install))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
internal fun InstalledAppItem(
    app: InstalledApp,
    context: Context,
    isConnected: Boolean,
    isInstalling: Boolean,
    onInstall: () -> Unit
) {
    val isPortalCompatible = InstallPolicy.isCompatible(app.minSdkVersion)
    val hasSplitApks = !app.splitSourceDirs.isNullOrEmpty()
    val pm = context.packageManager
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                // LoadApplicationIcon returneaza un Drawable; il convertim in ImageBitmap.
                val drawable = pm.getApplicationIcon(app.packageName)
                val bitmap = android.graphics.Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(64),
                    drawable.intrinsicHeight.coerceAtLeast(64),
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

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
            icon?.let { image ->
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = app.name,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(app.apkSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!InstallPolicy.isCompatible(app.minSdkVersion)) {
                Text(
                    text = stringResource(
                        R.string.portal_api_incompatible,
                        app.minSdkVersion,
                        PortalDeviceConfig.MAX_SDK
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                }
                if (hasSplitApks) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.split_apk_badge,
                                app.splitSourceDirs?.size ?: 0
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onInstall,
                enabled = isConnected && !isInstalling && isPortalCompatible,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.install))
            }
        }
    }
}
