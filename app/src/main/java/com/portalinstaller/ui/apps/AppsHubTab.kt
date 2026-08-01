package com.portalinstaller.ui.apps

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portalinstaller.R
import com.portalinstaller.data.store.AppProfile
import com.portalinstaller.data.InstalledApp
import com.portalinstaller.data.PortalAppInfo
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.InstallState
import com.portalinstaller.viewmodel.PortalAppsSort
import com.portalinstaller.viewmodel.ProfileApplyState
import com.portalinstaller.viewmodel.UninstallState

@Composable
internal fun AppsHubTab(
    isWifiConnected: Boolean,
    activePortalName: String?,
    activePortalAddress: String,
    apps: List<InstalledApp>,
    connectionState: ConnectionState,
    installState: InstallState,
    context: Context,
    selectedUri: Uri?,
    portalPackages: List<String>,
    portalApps: List<PortalAppInfo>,
    isLoadingPortalPackages: Boolean,
    uninstallState: UninstallState,
    portalAppsSort: PortalAppsSort,
    profiles: List<AppProfile>,
    profileApplyState: ProfileApplyState,
    onOpenDevices: () -> Unit,
    onPickFile: () -> Unit,
    onInstall: (InstalledApp) -> Unit,
    onInstallCustomApk: (Uri) -> Unit,
    onRefreshPortalApps: () -> Unit,
    onSortPortalApps: (PortalAppsSort) -> Unit,
    onUninstall: (String) -> Unit,
    onLaunchApp: (String) -> Unit,
    onForceStopApp: (String) -> Unit,
    onClearAppData: (String) -> Unit,
    onCreateProfile: () -> Unit,
    onApplyProfile: (AppProfile) -> Unit,
    onCancelApplyProfile: () -> Unit,
    onDeleteProfile: (AppProfile) -> Unit
) {
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isWifiConnected) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activePortalName ?: stringResource(R.string.active_portal),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = activePortalAddress,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onOpenDevices) {
                        Text(stringResource(R.string.change))
                    }
                }
            }
        }

        TabRow(selectedTabIndex = selectedSection) {
            Tab(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                text = { Text(stringResource(R.string.apps_from_phone)) }
            )
            Tab(
                selected = selectedSection == 1,
                onClick = {
                    selectedSection = 1
                    onRefreshPortalApps()
                },
                text = { Text(stringResource(R.string.apps_on_portal)) }
            )
            Tab(
                selected = selectedSection == 2,
                onClick = { selectedSection = 2 },
                text = { Text(stringResource(R.string.apps_profiles)) }
            )
        }

        if (!isWifiConnected && selectedSection != 2) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.no_portal_connected_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.no_portal_connected_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onOpenDevices,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(stringResource(R.string.open_devices))
                }
            }
        } else if (selectedSection == 0) {
            InstalledAppsTab(
                apps = apps,
                connectionState = connectionState,
                installState = installState,
                externalInstallBusy = profileApplyState is ProfileApplyState.Applying,
                context = context,
                selectedUri = selectedUri,
                onPickFile = onPickFile,
                onInstall = onInstall,
                onInstallCustomApk = onInstallCustomApk
            )
        } else if (selectedSection == 1) {
            PortalAppsTab(
                packages = portalPackages,
                apps = portalApps,
                isLoading = isLoadingPortalPackages,
                uninstallState = uninstallState,
                sort = portalAppsSort,
                onSortChange = onSortPortalApps,
                onRefresh = onRefreshPortalApps,
                onUninstall = onUninstall,
                onLaunch = onLaunchApp,
                onForceStop = onForceStopApp,
                onClearData = onClearAppData
            )
        } else {
            ProfilesTab(
                profiles = profiles,
                applyState = profileApplyState,
                isConnected = isWifiConnected,
                onCreate = onCreateProfile,
                onApply = onApplyProfile,
                onCancelApply = onCancelApplyProfile,
                onDelete = onDeleteProfile
            )
        }
    }
}
