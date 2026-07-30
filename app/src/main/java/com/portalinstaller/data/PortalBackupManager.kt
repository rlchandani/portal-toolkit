package com.portalinstaller.data

import android.content.Context
import android.net.Uri
import android.os.Build
import com.portalinstaller.R
import dadb.Dadb
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class PortalBackupSummary(
    val appCount: Int,
    val settingCount: Int
)

data class PortalRestoreSummary(
    val installedCount: Int,
    val failedPackages: List<String>,
    val restoredSettingCount: Int,
    val stoppedEarlyReason: String? = null
)

data class PortalBackupPreview(
    val createdAt: Long,
    val manufacturer: String,
    val model: String,
    val sdk: String,
    val appCount: Int,
    val settingCount: Int
)

class PortalBackupManager {

    companion object {
        private const val FORMAT_VERSION = 1
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val MAX_ARCHIVE_ENTRIES = 500
        private const val MAX_EXTRACTED_BYTES = 3L * 1024 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_CONSECUTIVE_RESTORE_FAILURES = 3

        private val BACKUP_SETTINGS = listOf(
            "system" to "screen_brightness",
            "system" to "screen_off_timeout",
            "system" to "accelerometer_rotation",
            "system" to "user_rotation",
            "system" to "volume_music",
            "system" to "volume_ring",
            "system" to "volume_alarm"
        )
    }

    suspend fun createBackup(
        context: Context,
        dadb: Dadb,
        destination: Uri,
        selectedPackages: Set<String>? = null,
        onProgress: (current: Int, total: Int, packageName: String) -> Unit
    ): PortalBackupSummary {
        val workDir = createWorkDir(context, "backup")
        try {
            val packages = listThirdPartyPackages(dadb).let { installed ->
                selectedPackages?.let { selected ->
                    installed.filter { it in selected }
                } ?: installed
            }
            val appRecords = packages.map { packageName ->
                val paths = dadb.shell("pm path ${shellQuote(packageName)}").output
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:") }
                    .filter { it.isNotBlank() }
                    .toList()
                require(paths.isNotEmpty()) { "No APK path returned for $packageName" }
                packageName to paths
            }

            val settings = readBackupSettings(dadb)
            val appsJson = JSONArray()
            appRecords.forEachIndexed { appIndex, (packageName, remotePaths) ->
                onProgress(appIndex + 1, appRecords.size, packageName)
                val packageDir = File(workDir, "apps/$packageName").apply { mkdirs() }
                val filesJson = JSONArray()

                remotePaths.forEachIndexed { fileIndex, remotePath ->
                    val remoteName = remotePath.substringAfterLast('/').ifBlank { "app.apk" }
                    val localName = "${fileIndex}_${sanitizeFileName(remoteName)}"
                    val localFile = File(packageDir, localName)
                    dadb.pull(localFile, remotePath)
                    require(localFile.isFile && localFile.length() > 0) {
                        "Empty APK received for $packageName"
                    }
                    filesJson.put(localName)
                }

                appsJson.put(
                    JSONObject()
                        .put("packageName", packageName)
                        .put("files", filesJson)
                )
            }
            onProgress(appRecords.size, appRecords.size, "")

            val manifest = JSONObject()
                .put("formatVersion", FORMAT_VERSION)
                .put("createdAt", System.currentTimeMillis())
                .put(
                    "device",
                    JSONObject()
                        .put("manufacturer", shellValue(dadb, "getprop ro.product.manufacturer"))
                        .put("model", shellValue(dadb, "getprop ro.product.model"))
                        .put("sdk", shellValue(dadb, "getprop ro.build.version.sdk"))
                )
                .put("settings", JSONObject(settings))
                .put("apps", appsJson)

            val output = requireNotNull(context.contentResolver.openOutputStream(destination, "w")) {
                "Could not open the selected backup file"
            }
            output.use { stream ->
                ZipOutputStream(stream.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    appRecords.forEach { (packageName, _) ->
                        val packageDir = File(workDir, "apps/$packageName")
                        packageDir.listFiles()
                            .orEmpty()
                            .sortedBy { it.name }
                            .forEach { apk ->
                                zip.putNextEntry(ZipEntry("apps/$packageName/${apk.name}"))
                                apk.inputStream().buffered().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                    }
                }
            }

            return PortalBackupSummary(appRecords.size, settings.size)
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun restoreBackup(
        context: Context,
        dadb: Dadb,
        source: Uri,
        onProgress: (current: Int, total: Int, packageName: String) -> Unit
    ): PortalRestoreSummary {
        val workDir = createWorkDir(context, "restore")
        try {
            extractArchive(context, source, workDir)
            val manifestFile = File(workDir, MANIFEST_ENTRY)
            require(manifestFile.isFile) { "The backup manifest is missing" }
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.optInt("formatVersion") == FORMAT_VERSION) {
                "Unsupported backup format"
            }

            val apps = manifest.optJSONArray("apps")
                ?: error("Backup manifest has no apps section")
            require(apps.length() <= MAX_ARCHIVE_ENTRIES) { "Backup contains too many apps" }
            var installedCount = 0
            val failedPackages = mutableListOf<String>()
            var consecutiveFailures = 0
            var stoppedEarlyReason: String? = null
            val seenPackages = mutableSetOf<String>()

            for (index in 0 until apps.length()) {
                val app = apps.getJSONObject(index)
                val packageName = app.getString("packageName")
                require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) {
                    "Invalid package name in backup"
                }
                require(seenPackages.add(packageName)) {
                    "Duplicate package in backup: $packageName"
                }
                onProgress(index + 1, apps.length(), packageName)
                val files = app.optJSONArray("files")
                    ?: error("Backup has no APK list for $packageName")
                require(files.length() in 1..MAX_ARCHIVE_ENTRIES) {
                    "Invalid APK list for $packageName"
                }
                val apks = buildList {
                    for (fileIndex in 0 until files.length()) {
                        val fileName = files.getString(fileIndex)
                        val apk = safeArchiveFile(workDir, "apps/$packageName/$fileName")
                        if (apk.isFile && apk.length() > 0) add(apk)
                    }
                }

                val installResult = runCatching {
                    require(apks.isNotEmpty()) { "No APK files for $packageName" }
                    apks.forEach { apk ->
                        val archivePackage = readArchivePackageName(context, apk)
                        require(archivePackage == packageName) {
                            "APK ${apk.name} package does not match $packageName"
                        }
                    }
                    installPackage(dadb, apks)
                }

                if (installResult.isSuccess) {
                    installedCount++
                    consecutiveFailures = 0
                } else {
                    failedPackages += packageName
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_RESTORE_FAILURES) {
                        val reason = installResult.exceptionOrNull()?.message
                            ?: "Multiple restore operations failed"
                        stoppedEarlyReason = context.getString(
                            R.string.backup_restore_stopped_reason,
                            consecutiveFailures,
                            reason
                        )
                        break
                    }
                }
            }
            val processedCount = if (stoppedEarlyReason == null) {
                apps.length()
            } else {
                installedCount + failedPackages.size
            }
            onProgress(processedCount, apps.length(), "")

            val restoredSettings = if (stoppedEarlyReason == null) {
                restoreSettings(
                    dadb,
                    manifest.optJSONObject("settings") ?: JSONObject()
                )
            } else {
                0
            }
            return PortalRestoreSummary(
                installedCount = installedCount,
                failedPackages = failedPackages,
                restoredSettingCount = restoredSettings,
                stoppedEarlyReason = stoppedEarlyReason
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    fun inspectBackup(context: Context, source: Uri): PortalBackupPreview {
        val input = requireNotNull(context.contentResolver.openInputStream(source)) {
            "Could not open the selected backup"
        }
        var preview: PortalBackupPreview? = null
        input.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                var entryCount = 0
                var manifestFound = false
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES) {
                        "Backup contains too many files"
                    }
                    if (!entry.isDirectory && entry.name == MANIFEST_ENTRY) {
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var manifestBytes = 0
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            manifestBytes += read
                            require(manifestBytes <= MAX_MANIFEST_BYTES) {
                                "Backup manifest is too large"
                            }
                            bytes.write(buffer, 0, read)
                        }

                        val manifest = JSONObject(bytes.toString(Charsets.UTF_8.name()))
                        require(manifest.optInt("formatVersion") == FORMAT_VERSION) {
                            "Unsupported backup format"
                        }
                        require(!manifestFound) { "Backup contains multiple manifests" }
                        manifestFound = true
                        val apps = manifest.optJSONArray("apps")
                            ?: error("Backup manifest has no apps section")
                        require(apps.length() <= MAX_ARCHIVE_ENTRIES) {
                            "Backup contains too many apps"
                        }
                        val settings = manifest.optJSONObject("settings") ?: JSONObject()
                        val device = manifest.optJSONObject("device") ?: JSONObject()
                        preview = PortalBackupPreview(
                            createdAt = manifest.optLong("createdAt"),
                            manufacturer = device.optString("manufacturer"),
                            model = device.optString("model"),
                            sdk = device.optString("sdk"),
                            appCount = apps.length(),
                            settingCount = settings.length()
                        )
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return preview ?: error("The backup manifest is missing")
    }

    private fun listThirdPartyPackages(dadb: Dadb): List<String> {
        return dadb.shell("pm list packages -3").output
            .lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.matches(Regex("[A-Za-z0-9_.]+")) }
            .distinct()
            .sorted()
            .toList()
    }

    private fun readBackupSettings(dadb: Dadb): Map<String, String> {
        return BACKUP_SETTINGS.mapNotNull { (namespace, name) ->
            val value = runCatching {
                dadb.shell("settings get $namespace $name").output.trim()
            }.getOrNull()
            if (value.isNullOrBlank() || value == "null" || !isSafeSettingValue(value)) {
                null
            } else {
                "$namespace.$name" to value
            }
        }.toMap()
    }

    private fun restoreSettings(dadb: Dadb, settings: JSONObject): Int {
        var restored = 0
        BACKUP_SETTINGS.forEach { (namespace, name) ->
            val value = settings.optString("$namespace.$name")
            if (value.isNotBlank() && isSafeSettingValue(value)) {
                val output = runCatching {
                    dadb.shell("settings put $namespace $name ${shellQuote(value)}").output
                }
                if (output.isSuccess) restored++
            }
        }
        return restored
    }

    private fun installPackage(dadb: Dadb, apks: List<File>) {
        if (apks.size > 1) {
            dadb.installMultiple(apks, "-r")
            return
        }

        val apk = apks.single()
        val remotePath = "/data/local/tmp/portal_restore_${UUID.randomUUID()}.apk"
        try {
            dadb.push(apk, remotePath)
            val response = dadb.shell("pm install -r ${shellQuote(remotePath)}").allOutput.trim()
            require(response.startsWith("Success", ignoreCase = true)) {
                response.ifBlank { "Portal did not confirm APK installation" }
            }
        } finally {
            runCatching { dadb.shell("rm -f ${shellQuote(remotePath)}") }
        }
    }

    private fun readArchivePackageName(context: Context, apk: File): String? {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }
        return packageInfo?.packageName
    }

    private fun extractArchive(context: Context, source: Uri, destination: File) {
        val input = requireNotNull(context.contentResolver.openInputStream(source)) {
            "Could not open the selected backup"
        }
        input.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                var entryCount = 0
                var extractedBytes = 0L
                var manifestFound = false
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES) { "Backup contains too many files" }
                    val outputFile = safeArchiveFile(destination, entry.name)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var manifestBytes = 0
                            if (entry.name == MANIFEST_ENTRY) {
                                require(!manifestFound) {
                                    "Backup contains multiple manifests"
                                }
                                manifestFound = true
                            }
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                extractedBytes += read
                                if (entry.name == MANIFEST_ENTRY) {
                                    manifestBytes += read
                                    require(manifestBytes <= MAX_MANIFEST_BYTES) {
                                        "Backup manifest is too large"
                                    }
                                }
                                require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                                    "Backup is larger than the supported limit"
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }

    private fun safeArchiveFile(root: File, entryName: String): File {
        val file = File(root, entryName)
        val rootPath = root.canonicalPath + File.separator
        require(file.canonicalPath.startsWith(rootPath)) { "Invalid file path in backup" }
        return file
    }

    private fun createWorkDir(context: Context, prefix: String): File {
        return File(context.cacheDir, "portal_${prefix}_${UUID.randomUUID()}").apply {
            check(mkdirs()) { "Could not create temporary backup storage" }
        }
    }

    private fun shellValue(dadb: Dadb, command: String): String {
        return runCatching { dadb.shell(command).output.trim() }.getOrDefault("")
    }

    private fun sanitizeFileName(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun isSafeSettingValue(value: String): Boolean {
        return value.length <= 64 && value.matches(Regex("[A-Za-z0-9._+-]+"))
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
