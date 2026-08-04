package com.portalinstaller.ui

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.portalinstaller.R
import com.portalinstaller.viewmodel.AppActionState
import com.portalinstaller.viewmodel.AppsViewModel
import com.portalinstaller.viewmodel.BackupState
import com.portalinstaller.viewmodel.BackupViewModel
import com.portalinstaller.viewmodel.MirrorSaveState
import com.portalinstaller.viewmodel.MirrorViewModel
import com.portalinstaller.viewmodel.ConnectionState
import com.portalinstaller.viewmodel.ConnectionViewModel
import com.portalinstaller.viewmodel.FileManagerViewModel
import com.portalinstaller.viewmodel.FileTransferState
import com.portalinstaller.viewmodel.InstallState
import com.portalinstaller.viewmodel.PortalDiscoveryState
import com.portalinstaller.viewmodel.PowerControlState
import com.portalinstaller.viewmodel.ProfileApplyState
import com.portalinstaller.viewmodel.ScreensaverViewModel
import com.portalinstaller.viewmodel.UninstallState
import com.portalinstaller.viewmodel.UploadSummaryState
import com.portalinstaller.viewmodel.WirelessAdbState

/**
 * Grupeaza toate efectele care traduc starile ViewModel-urilor in mesaje
 * Snackbar. Nu deseneaza nimic: fiecare LaunchedEffect observa o stare, afiseaza
 * mesajul potrivit si apoi curata starea. Extras din MainScreen ca sa tinem
 * fisierul principal sub limita de dimensiune si sa izolam cablajul de notificari.
 *
 * Fiecare efect ramane independent (cheie proprie, fara stare locala partajata),
 * deci comportamentul este identic cu varianta inline anterioara.
 */
@Composable
internal fun MainScreenSnackbarEffects(
    snackbarHostState: SnackbarHostState,
    context: Context,
    connectionState: ConnectionState,
    installState: InstallState,
    uninstallState: UninstallState,
    screensaverUploadState: UploadSummaryState,
    screensaverNotConnected: Boolean,
    wirelessAdbState: WirelessAdbState,
    portalPackagesError: String?,
    portalDiscoveryState: PortalDiscoveryState,
    backupState: BackupState,
    profileApplyState: ProfileApplyState,
    appActionState: AppActionState,
    powerControlState: PowerControlState,
    mirrorSaveState: MirrorSaveState,
    mirrorNotConnected: Boolean,
    fileTransferState: FileTransferState,
    fileNotConnected: Boolean,
    connectionViewModel: ConnectionViewModel,
    appsViewModel: AppsViewModel,
    backupViewModel: BackupViewModel,
    screensaverViewModel: ScreensaverViewModel,
    mirrorViewModel: MirrorViewModel,
    fileManagerViewModel: FileManagerViewModel
) {
    // Afiseaza Snackbar pentru stari importante si erori.
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Error) {
            snackbarHostState.showSnackbar(connectionState.message)
            connectionViewModel.clearConnectionError()
        }
    }

    LaunchedEffect(installState) {
        when (val state = installState) {
            is InstallState.Success -> {
                snackbarHostState.showSnackbar(context.getString(R.string.snackbar_install_success))
                appsViewModel.clearInstallState()
            }
            is InstallState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                appsViewModel.clearInstallState()
            }
            else -> {}
        }
    }

    LaunchedEffect(screensaverUploadState) {
        when (val state = screensaverUploadState) {
            is UploadSummaryState.Done -> {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.screensaver_upload_summary,
                        state.uploaded,
                        state.skipped,
                        state.failed
                    )
                )
                screensaverViewModel.clearUploadState()
            }
            is UploadSummaryState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                screensaverViewModel.clearUploadState()
            }
            else -> {}
        }
    }

    LaunchedEffect(screensaverNotConnected) {
        if (screensaverNotConnected) {
            snackbarHostState.showSnackbar(context.getString(R.string.shell_not_connected))
            screensaverViewModel.clearNotConnected()
        }
    }

    LaunchedEffect(uninstallState) {
        when (val state = uninstallState) {
            is UninstallState.Success -> {
                snackbarHostState.showSnackbar(context.getString(R.string.snackbar_uninstall_success))
                appsViewModel.clearUninstallState()
            }
            is UninstallState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                appsViewModel.clearUninstallState()
            }
            else -> {}
        }
    }

    LaunchedEffect(wirelessAdbState) {
        when (val state = wirelessAdbState) {
            is WirelessAdbState.Success -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.snackbar_wireless_adb_enabled)
                )
                connectionViewModel.clearWirelessAdbState()
            }
            is WirelessAdbState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                connectionViewModel.clearWirelessAdbState()
            }
            else -> {}
        }
    }

    LaunchedEffect(portalPackagesError) {
        portalPackagesError?.let { message ->
            snackbarHostState.showSnackbar(message)
            appsViewModel.clearPortalPackagesError()
        }
    }

    LaunchedEffect(portalDiscoveryState) {
        if (portalDiscoveryState is PortalDiscoveryState.Failed) {
            snackbarHostState.showSnackbar(portalDiscoveryState.message)
            connectionViewModel.clearPortalDiscoveryState()
        }
    }

    LaunchedEffect(backupState) {
        when (val state = backupState) {
            is BackupState.Success -> {
                snackbarHostState.showSnackbar(state.message)
                backupViewModel.clearBackupState()
            }
            is BackupState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                backupViewModel.clearBackupState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(profileApplyState) {
        when (val state = profileApplyState) {
            is ProfileApplyState.Done -> {
                val message = if (state.failed.isEmpty()) {
                    context.getString(
                        R.string.profile_apply_success,
                        state.profileName,
                        state.succeeded.size
                    )
                } else {
                    context.getString(
                        R.string.profile_apply_partial,
                        state.profileName,
                        state.succeeded.size,
                        state.failed.size
                    )
                }
                snackbarHostState.showSnackbar(message)
                appsViewModel.clearProfileApplyState()
            }
            is ProfileApplyState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                appsViewModel.clearProfileApplyState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(appActionState) {
        when (val state = appActionState) {
            is AppActionState.Done -> {
                snackbarHostState.showSnackbar(state.message)
                appsViewModel.clearAppActionState()
            }
            is AppActionState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                appsViewModel.clearAppActionState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(powerControlState) {
        when (val state = powerControlState) {
            is PowerControlState.Success -> {
                snackbarHostState.showSnackbar(state.message)
                connectionViewModel.clearPowerControlState()
            }
            is PowerControlState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                connectionViewModel.clearPowerControlState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(mirrorSaveState) {
        when (val state = mirrorSaveState) {
            is MirrorSaveState.Saved -> {
                snackbarHostState.showSnackbar(context.getString(R.string.mirror_saved))
                mirrorViewModel.clearSaveState()
            }
            is MirrorSaveState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                mirrorViewModel.clearSaveState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(mirrorNotConnected) {
        if (mirrorNotConnected) {
            snackbarHostState.showSnackbar(context.getString(R.string.mirror_not_connected))
            mirrorViewModel.clearNotConnected()
        }
    }

    LaunchedEffect(fileTransferState) {
        when (val state = fileTransferState) {
            is FileTransferState.Done -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.files_transfer_done, state.pulled, state.pushed)
                )
                fileManagerViewModel.clearTransferState()
            }
            is FileTransferState.Failed -> {
                snackbarHostState.showSnackbar(state.message)
                fileManagerViewModel.clearTransferState()
            }
            else -> Unit
        }
    }

    LaunchedEffect(fileNotConnected) {
        if (fileNotConnected) {
            snackbarHostState.showSnackbar(context.getString(R.string.shell_not_connected))
            fileManagerViewModel.clearNotConnected()
        }
    }
}
