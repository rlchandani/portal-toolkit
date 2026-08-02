package com.portalinstaller.data

import android.content.Context
import android.content.pm.PackageManager
import com.portalinstaller.R
import com.portalinstaller.data.apps.AppMetadataCache
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

data class DeviceStatus(
    val storageFreeBytes: Long?,
    val storageTotalBytes: Long?,
    val ramFreeBytes: Long?,
    val ramTotalBytes: Long?,
    val manufacturer: String,
    val model: String,
    val sdk: String,
    val uptimeSeconds: Long?
)

data class PortalIdentity(
    val manufacturer: String,
    val model: String,
    val brand: String
) {
    val displayName: String
        get() = listOf(manufacturer, model)
            .filter { it.isNotBlank() && it != "unknown" }
            .joinToString(" ")
            .ifBlank { "unknown" }
}

data class OsUpdateBlockStatus(
    val supportedPackages: List<String>,
    val blockedPackages: List<String>
) {
    val isBlocked: Boolean
        get() = supportedPackages.isNotEmpty() &&
            blockedPackages.containsAll(supportedPackages)
}

class PortalConnectionManager {

    companion object {
        const val PORTAL_PHOTOS_DIR = "/sdcard/Pictures/PortalInstaller"
        private val OTA_PACKAGES = listOf(
            "com.facebook.aloha.alohaotasetup",
            "com.facebook.aloha.otaui"
        )
    }

    suspend fun connect(context: Context, ip: String, port: Int = 5555): Result<Dadb> {
        return withContext(Dispatchers.IO) {
            try {
                val keyPair = AdbKeyPairProvider.getOrCreate(context)
                val dadb = Dadb.create(ip, port, keyPair)
                val identity = readPortalIdentity(dadb)
                if (!looksLikePortal(identity)) {
                    runCatching { dadb.close() }
                    return@withContext Result.failure(
                        AdbError.NotPortal(
                            context.getString(
                                R.string.error_not_portal_device,
                                ip,
                                port,
                                identity.displayName
                            )
                        )
                    )
                }
                Result.success(dadb)
            } catch (e: Exception) {
                android.util.Log.e("PortalDebug", "Eroare conectare: ${e.javaClass.name} - ${e.message}", e)
                Result.failure(AdbError.ConnectionRefused(ip, port, e, context))
            }
        }
    }

    fun readPortalIdentity(dadb: Dadb): PortalIdentity {
        return PortalIdentity(
            manufacturer = readProperty(dadb, "ro.product.manufacturer").ifBlank { "unknown" },
            model = readProperty(dadb, "ro.product.model").ifBlank { "unknown" },
            brand = readProperty(dadb, "ro.product.brand").ifBlank { "unknown" }
        )
    }

    fun looksLikePortal(identity: PortalIdentity): Boolean {
        val haystack = listOf(
            identity.manufacturer,
            identity.model,
            identity.brand
        ).joinToString(" ").lowercase()
        return haystack.contains("portal") ||
            haystack.contains("facebook") ||
            haystack.contains("meta")
    }

    suspend fun installApk(context: Context, dadb: Dadb, apkFile: File): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                dadb.install(apkFile)
                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("PortalDebug", "Eroare instalare: ${e.javaClass.name} - ${e.message}", e)
                Result.failure(
                    AdbError.InstallFailed(
                        context.getString(
                            R.string.error_install_failed_reason,
                            formatAdbReason(
                                context,
                                "${e.javaClass.simpleName}: ${e.message}",
                                context.getString(R.string.error_install_failed_generic)
                            )
                        )
                    )
                )
            }
        }
    }

    suspend fun installMultipleApks(context: Context, dadb: Dadb, apks: List<File>): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                dadb.installMultiple(apks)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(
                    AdbError.InstallFailed(
                        formatAdbReason(
                            context,
                            e.message,
                            context.getString(R.string.error_install_failed_generic)
                        )
                    )
                )
            }
        }
    }

    suspend fun pushPhoto(
        context: Context,
        dadb: Dadb,
        localFile: File,
        remoteDir: String = PORTAL_PHOTOS_DIR
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                dadb.shell("mkdir -p ${shellQuote(remoteDir)}")
                val remotePath = "$remoteDir/${localFile.name}"
                dadb.push(localFile, remotePath)
                dadb.shell(
                    "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d ${shellQuote("file://$remotePath")}"
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(
                    AdbError.UploadFailed(
                        localFile.name,
                        formatAdbReason(
                            context,
                            e.message,
                            context.getString(R.string.error_unknown)
                        ),
                        context
                    )
                )
            }
        }
    }

    suspend fun listPortalPackages(context: Context, dadb: Dadb): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val packages = dadb.shell("pm list packages -3").output
                    .lineSequence()
                    .mapNotNull { line ->
                        line.trim()
                            .takeIf { it.startsWith("package:") }
                            ?.removePrefix("package:")
                            ?.takeIf { it.isNotBlank() }
                    }
                    .sorted()
                    .toList()
                Result.success(packages)
            } catch (e: Exception) {
                Result.failure(
                    AdbError.Unknown(
                        formatAdbReason(
                            context,
                            e.message,
                            context.getString(R.string.error_list_portal_apps)
                        )
                    )
                )
            }
        }
    }

    suspend fun listPortalApps(context: Context, dadb: Dadb): Result<List<PortalAppInfo>> {
        return withContext(Dispatchers.IO) {
            try {
                val output = dadb.shell("pm list packages -3").output
                val packages = output.lines()
                    .mapNotNull { line ->
                        line.trim()
                            .takeIf { it.startsWith("package:") }
                            ?.removePrefix("package:")
                            ?.takeIf { it.isNotBlank() }
                    }
                    .sorted()
                Result.success(
                    packages.map { packageName ->
                        loadPortalAppInfo(dadb, packageName)
                    }
                )
            } catch (e: Exception) {
                Result.failure(
                    AdbError.Unknown(
                        formatAdbReason(
                            context,
                            e.message,
                            context.getString(R.string.error_list_portal_apps)
                        )
                    )
                )
            }
        }
    }

    suspend fun uninstallApp(context: Context, dadb: Dadb, packageName: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val output = dadb.shell("pm uninstall $packageName").output
                if (output.contains("Success", ignoreCase = true)) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        AdbError.InstallFailed(
                            formatAdbReason(
                                context,
                                output.trim(),
                                context.getString(R.string.error_uninstall_failed_generic)
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(
                    AdbError.InstallFailed(
                        formatAdbReason(
                            context,
                            e.message,
                            context.getString(R.string.error_uninstall_failed_generic)
                        )
                    )
                )
            }
        }
    }

    suspend fun enableWirelessAdb(context: Context, dadb: Dadb, port: Int): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                require(port in 1..65535) {
                    context.getString(R.string.error_invalid_port)
                }
                val ipGuess = detectPortalIpAddress(dadb)
                    ?: return@withContext Result.failure(
                        AdbError.Unknown(
                            context.getString(R.string.error_wireless_adb_ip_not_found)
                        )
                    )

                dadb.shell("setprop service.adb.tcp.port $port")
                val appliedPort = dadb.shell("getprop service.adb.tcp.port").output.trim()
                if (appliedPort != port.toString()) {
                    return@withContext Result.failure(
                        AdbError.Unknown(
                            context.getString(
                                R.string.error_wireless_adb_not_confirmed,
                                appliedPort.ifBlank { context.getString(R.string.empty_value) }
                            )
                        )
                    )
                }

                runCatching { dadb.shell("stop adbd") }
                runCatching { dadb.shell("start adbd") }

                if (!verifyWirelessAdbPort(ipGuess, port)) {
                    return@withContext Result.failure(
                        AdbError.Unknown(
                            context.getString(
                                R.string.error_wireless_adb_connection_not_verified,
                                ipGuess,
                                port
                            )
                        )
                    )
                }

                val message = buildString {
                    append(context.getString(R.string.wireless_adb_enabled_on_port_prefix))
                    append(port)
                    if (!ipGuess.isNullOrBlank()) {
                        append(context.getString(R.string.wireless_adb_connect_after_prefix))
                        append(ipGuess)
                        append(":")
                        append(port)
                    }
                }

                Result.success(message)
            } catch (e: Exception) {
                Result.failure(
                    AdbError.Unknown(
                        formatAdbReason(
                            context,
                            e.message,
                            context.getString(R.string.error_enable_wireless_adb)
                        )
                    )
                )
            }
        }
    }

    suspend fun getDeviceStatus(dadb: Dadb): DeviceStatus {
        return withContext(Dispatchers.IO) {
            val storage = runCatching { parseDf(dadb.shell("df /sdcard").output) }.getOrNull()
            val memInfo = runCatching { MemInfoParser.parse(dadb.shell("cat /proc/meminfo").output) }.getOrNull()
            val uptime = runCatching { parseUptimeSeconds(dadb.shell("cat /proc/uptime").output) }.getOrNull()
            DeviceStatus(
                storageFreeBytes = storage?.first,
                storageTotalBytes = storage?.second,
                ramFreeBytes = memInfo?.availableBytes,
                ramTotalBytes = memInfo?.totalBytes,
                manufacturer = readProperty(dadb, "ro.product.manufacturer"),
                model = readProperty(dadb, "ro.product.model"),
                sdk = readProperty(dadb, "ro.build.version.sdk"),
                uptimeSeconds = uptime
            )
        }
    }

    private suspend fun verifyWirelessAdbPort(ip: String, port: Int): Boolean {
        repeat(10) { attempt ->
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), 750)
                }
            }.onSuccess { return true }
            if (attempt < 9) delay(500)
        }
        return false
    }

    suspend fun getOsUpdateBlockStatus(dadb: Dadb): OsUpdateBlockStatus {
        return withContext(Dispatchers.IO) {
            val installed = runCatching {
                dadb.shell("pm list packages").output
                    .lineSequence()
                    .map { it.trim().removePrefix("package:") }
                    .toSet()
            }.getOrDefault(emptySet())
            val supported = OTA_PACKAGES.filter { it in installed }
            val disabled = runCatching {
                dadb.shell("pm list packages -d").output
                    .lineSequence()
                    .map { it.trim().removePrefix("package:") }
                    .toSet()
            }.getOrDefault(emptySet())
            OsUpdateBlockStatus(supported, supported.filter { it in disabled })
        }
    }

    private fun loadPortalAppInfo(dadb: Dadb, packageName: String): PortalAppInfo {
        val dump = runCatching {
            dadb.shell("dumpsys package ${shellQuote(packageName)}").output
        }.getOrDefault("")
        val codePath = Regex("(?:^|\\n)\\s*codePath=([^\\s]+)")
            .find(dump)?.groupValues?.getOrNull(1)
        val sizeBytes = codePath?.let { path ->
            runCatching {
                val kb = Regex("\\d+").find(
                    dadb.shell("du -sk ${shellQuote(path)}").output
                )?.value?.toLongOrNull() ?: 0L
                kb * 1024
            }.getOrDefault(0L)
        } ?: 0L
        val versionName = Regex("(?:^|\\n)\\s*versionName=([^\\s]+)")
            .find(dump)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "-" }
        val versionCode = Regex("versionCode=(\\d+)")
            .find(dump)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        return PortalAppInfo(
            packageName = packageName,
            name = packageName,
            versionName = versionName,
            versionCode = versionCode,
            sizeBytes = sizeBytes,
            iconApkPath = null
        )
    }

    /**
     * Imbogateste o aplicatie Portal cu eticheta lizibila si iconita, extrase
     * din `base.apk`. Descarcarea APK-ului este costisitoare, deci rezultatul
     * este pus in cache pe disc dupa cheia pachet+versiune ([AppMetadataCache]).
     * Intoarce aplicatia neschimbata daca extragerea esueaza.
     */
    suspend fun enrichPortalApp(context: Context, dadb: Dadb, app: PortalAppInfo): PortalAppInfo {
        return withContext(Dispatchers.IO) {
            val cacheDir = context.cacheDir
            val key = AppMetadataCache.keyFor(app.packageName, app.versionCode)
            val iconFile = AppMetadataCache.iconFile(cacheDir, key)
            val labelFile = AppMetadataCache.labelFile(cacheDir, key)

            if (AppMetadataCache.isCached(cacheDir, key)) {
                return@withContext app.copy(
                    label = labelFile.readText().ifBlank { app.name },
                    iconCachePath = iconFile.absolutePath
                )
            }

            runCatching {
                // Afla calea base.apk pe Portal.
                val apkPath = dadb.shell("pm path ${shellQuote(app.packageName)}").output
                    .lineSequence()
                    .map { it.trim().removePrefix("package:") }
                    .firstOrNull { it.endsWith("base.apk") || it.endsWith(".apk") }
                    ?: return@runCatching app

                val tempApk = File(cacheDir, "meta_${key.replace('/', '_')}.apk")
                dadb.pull(tempApk, apkPath)

                val pm = context.packageManager
                val info = pm.getPackageArchiveInfo(tempApk.absolutePath, 0)
                val appInfo = info?.applicationInfo
                if (appInfo != null) {
                    appInfo.sourceDir = tempApk.absolutePath
                    appInfo.publicSourceDir = tempApk.absolutePath
                    val label = appInfo.loadLabel(pm).toString().ifBlank { app.name }
                    labelFile.writeText(label)
                    runCatching { writeIconPng(appInfo.loadIcon(pm), iconFile) }
                    tempApk.delete()
                    return@runCatching app.copy(
                        label = label,
                        iconCachePath = if (iconFile.exists()) iconFile.absolutePath else null
                    )
                }
                tempApk.delete()
                app
            }.getOrDefault(app)
        }
    }

    private fun writeIconPng(drawable: android.graphics.drawable.Drawable, dest: File) {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        dest.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
        }
    }

    /** Opreste fortat o aplicatie de pe Portal (`am force-stop`). */
    suspend fun forceStopApp(context: Context, dadb: Dadb, packageName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                dadb.shell("am force-stop ${shellQuote(packageName)}")
                Unit
            }
        }

    /** Sterge datele unei aplicatii de pe Portal (`pm clear`). */
    suspend fun clearAppData(context: Context, dadb: Dadb, packageName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val out = dadb.shell("pm clear ${shellQuote(packageName)}").allOutput
                require(out.contains("Success", ignoreCase = true)) {
                    out.ifBlank { context.getString(R.string.error_unknown) }
                }
                Unit
            }
        }


    suspend fun setOsUpdatesBlocked(dadb: Dadb, blocked: Boolean): Result<OsUpdateBlockStatus> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val before = getOsUpdateBlockStatus(dadb)
                require(before.supportedPackages.isNotEmpty()) {
                    "Portal update components were not found"
                }
                before.supportedPackages.forEach { packageName ->
                    val command = if (blocked) {
                        "pm disable-user --user 0 $packageName"
                    } else {
                        "pm enable --user 0 $packageName"
                    }
                    val output = dadb.shell(command).allOutput.trim()
                    require(!output.contains("Exception", ignoreCase = true)) {
                        output.ifBlank { "Portal rejected the update setting" }
                    }
                }
                getOsUpdateBlockStatus(dadb)
            }
        }
    }

    /** Repornire normală sau în recovery. Portal-ul se va deconecta imediat. */
    suspend fun reboot(dadb: Dadb, recovery: Boolean): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val command = if (recovery) "reboot recovery" else "reboot"
                dadb.shell(command)
                Unit
            }
        }
    }

    /** Aprinde (keyevent 224) sau stinge (keyevent 223) ecranul Portal-ului. */
    suspend fun setScreenOn(dadb: Dadb, on: Boolean): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val keyevent = if (on) 224 else 223
                dadb.shell("input keyevent $keyevent")
                Unit
            }
        }
    }

    /** Lansează o aplicație pe Portal prin monkey (categoria LAUNCHER). */
    suspend fun launchApp(dadb: Dadb, packageName: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val output = dadb.shell(
                    "monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1"
                ).allOutput
                require(!output.contains("No activities found", ignoreCase = true)) {
                    "Nu s-a găsit o activitate de lansare pentru $packageName"
                }
                Unit
            }
        }
    }

    private fun readProperty(dadb: Dadb, name: String): String {
        return runCatching { dadb.shell("getprop $name").output.trim() }.getOrDefault("")
    }

    suspend fun loadInstalledApps(context: Context): List<InstalledApp> {
        return withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                },
                PackageManager.MATCH_ALL
            )

            val launcherPackages = intent.map { it.activityInfo.packageName }.toSet()

            pm.getInstalledApplications(PackageManager.MATCH_ALL)
                .filter { it.packageName in launcherPackages }
                .filter { it.packageName != context.packageName }
                .filter { appInfo ->
                    val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    !isSystemApp
                }
                .map { appInfo ->
                    InstalledApp(
                        name = appInfo.loadLabel(pm).toString(),
                        packageName = appInfo.packageName,
                        sourceDir = appInfo.sourceDir,
                        splitSourceDirs = appInfo.splitSourceDirs?.toList(),
                        apkSize = File(appInfo.sourceDir).length(),
                        minSdkVersion = appInfo.minSdkVersion
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun parseDf(output: String): Pair<Long, Long>? {
        val dataLine = output.lines().drop(1).firstOrNull { it.isNotBlank() } ?: return null
        val columns = dataLine.trim().split(Regex("\\s+"))
        if (columns.size < 4) return null
        val totalKb = columns[1].toLongOrNull() ?: return null
        val availableKb = columns[3].toLongOrNull() ?: return null
        return (availableKb * 1024) to (totalKb * 1024)
    }

    private fun detectPortalIpAddress(dadb: Dadb): String? {
        val commands = listOf(
            "getprop dhcp.wlan0.ipaddress",
            "getprop dhcp.eth0.ipaddress",
            "getprop dhcp.wifi.ipaddress",
            "ip -f inet addr show wlan0",
            "ifconfig wlan0"
        )

        commands.forEach { command ->
            val output = runCatching { dadb.shell(command).output.trim() }.getOrNull().orEmpty()
            val ip = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""").find(output)?.value
            if (!ip.isNullOrBlank() && ip != "127.0.0.1" && ip != "0.0.0.0") {
                return ip
            }
        }

        return null
    }

    // Delegat catre formatterul partajat; pastrat privat pentru call-site-urile existente.
    private fun formatAdbReason(context: Context, rawMessage: String?, fallback: String): String =
        AdbErrors.formatReason(context, rawMessage, fallback)
}

sealed class AdbError(override val message: String) : Exception(message) {
    data class ConnectionRefused(
        val ip: String,
        val port: Int,
        val originalCause: Throwable,
        val context: Context
    ) : AdbError(context.getString(R.string.error_connection_refused, ip, port))

    data class InstallFailed(val reason: String) : AdbError(reason)

    data class NotPortal(val reason: String) : AdbError(reason)

    data class UploadFailed(val fileName: String, val reason: String, val context: Context) :
        AdbError(context.getString(R.string.error_upload_failed_reason, fileName, reason))

    data class Unknown(val detail: String) : AdbError(detail)
}
