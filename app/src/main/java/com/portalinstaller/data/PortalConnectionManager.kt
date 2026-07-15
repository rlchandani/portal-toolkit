package com.portalinstaller.data

import android.content.Context
import android.content.pm.PackageManager
import com.portalinstaller.R
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DeviceStatus(
    val storageFreeBytes: Long?,
    val storageTotalBytes: Long?,
    val ramFreeBytes: Long?
)

class PortalConnectionManager {

    companion object {
        const val PORTAL_PHOTOS_DIR = "/sdcard/Pictures/PortalInstaller"
    }

    suspend fun connect(context: Context, ip: String, port: Int = 5555): Result<Dadb> {
        return withContext(Dispatchers.IO) {
            try {
                val keyPair = AdbKeyPairProvider.getOrCreate(context)
                val dadb = Dadb.create(ip, port, keyPair)
                Result.success(dadb)
            } catch (e: Exception) {
                android.util.Log.e("PortalDebug", "Eroare conectare: ${e.javaClass.name} - ${e.message}", e)
                Result.failure(AdbError.ConnectionRefused(ip, port, e, context))
            }
        }
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
                val output = dadb.shell("pm list packages -3").output
                val packages = output.lines()
                    .mapNotNull { line ->
                        line.trim()
                            .takeIf { it.startsWith("package:") }
                            ?.removePrefix("package:")
                            ?.takeIf { it.isNotBlank() }
                    }
                    .sorted()
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
                val ipGuess = detectPortalIpAddress(dadb)

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
            val ramFree = runCatching { parseMemAvailable(dadb.shell("cat /proc/meminfo").output) }.getOrNull()
            DeviceStatus(
                storageFreeBytes = storage?.first,
                storageTotalBytes = storage?.second,
                ramFreeBytes = ramFree
            )
        }
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

    private fun parseMemAvailable(output: String): Long? {
        val line = output.lines().firstOrNull { it.startsWith("MemAvailable") } ?: return null
        val kb = Regex("""\d+""").find(line)?.value?.toLongOrNull() ?: return null
        return kb * 1024
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

    private fun formatAdbReason(context: Context, rawMessage: String?, fallback: String): String {
        val message = rawMessage?.trim().orEmpty()
        if (message.isBlank()) return fallback

        val normalized = message.lowercase()
        return when {
            normalized.contains("device offline") ->
                context.getString(R.string.error_device_offline)
            normalized.contains("broken pipe") || normalized.contains("connection reset") || normalized.contains("closed") ->
                context.getString(R.string.error_connection_closed)
            normalized.contains("timeout") || normalized.contains("timed out") ->
                context.getString(R.string.error_operation_timed_out)
            normalized.contains("permission denied") ->
                context.getString(R.string.error_permission_denied)
            normalized.contains("no space left") ->
                context.getString(R.string.error_no_space_left)
            normalized.contains("install_failed_version_downgrade") ->
                context.getString(R.string.error_install_version_downgrade)
            normalized.contains("install_failed_update_incompatible") ->
                context.getString(R.string.error_install_update_incompatible)
            normalized.contains("install_failed_invalid_apk") ->
                context.getString(R.string.error_install_invalid_apk)
            normalized.contains("install_failed_older_sdk") ->
                context.getString(R.string.error_install_older_sdk)
            normalized.contains("install_parse_failed") ->
                context.getString(R.string.error_install_parse_failed)
            normalized.contains("securityexception") ->
                context.getString(R.string.error_security_exception)
            else -> message
        }
    }
}

sealed class AdbError(override val message: String) : Exception(message) {
    data class ConnectionRefused(
        val ip: String,
        val port: Int,
        val originalCause: Throwable,
        val context: Context
    ) : AdbError(context.getString(R.string.error_connection_refused, ip, port))

    data class InstallFailed(val reason: String) : AdbError(reason)

    data class UploadFailed(val fileName: String, val reason: String, val context: Context) :
        AdbError(context.getString(R.string.error_upload_failed_reason, fileName, reason))

    data class Unknown(val detail: String) : AdbError(detail)
}
