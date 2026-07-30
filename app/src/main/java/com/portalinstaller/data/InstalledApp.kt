package com.portalinstaller.data

/**
 * Reprezintă o aplicație instalată pe telefon, candidată pentru
 * transfer pe Portal.
 *
 * minSdkVersion e folosit pentru avertizarea preventivă din UI —
 * comparăm cu API-ul cunoscut al Portal-ului (vezi
 * PORTAL_TARGET_API_LEVEL în MainScreen.kt) ca să marcăm din timp
 * aplicațiile incompatibile, înainte ca instalarea să eșueze cu
 * INSTALL_FAILED_OLDER_SDK.
 */
data class InstalledApp(
    val name: String,
    val packageName: String,
    val sourceDir: String,
    val splitSourceDirs: List<String>?,
    val apkSize: Long,
    val minSdkVersion: Int
)

data class PortalAppInfo(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val iconApkPath: String?
)
