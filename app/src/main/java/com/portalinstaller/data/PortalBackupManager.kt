package com.portalinstaller.data

import android.content.Context
import android.net.Uri
import android.os.Build
import com.portalinstaller.R
import com.portalinstaller.data.adb.shellQuote
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
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
    val stoppedEarlyReason: String? = null,
    val failedSettings: List<String> = emptyList()
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
        internal const val FORMAT_VERSION = 1
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val MAX_ARCHIVE_ENTRIES = 500
        private const val MAX_EXTRACTED_BYTES = 3L * 1024 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        internal const val MAX_CONSECUTIVE_RESTORE_FAILURES = 3

        private val PACKAGE_NAME_REGEX = Regex("[A-Za-z0-9_.]+")

        /** Formatul de backup pe care îl putem restaura. Versiuni necunoscute sunt respinse. */
        internal fun isSupportedFormat(version: Int): Boolean = version == FORMAT_VERSION

        /** Nume de pachet valid: doar litere/cifre/`_`/`.` (evită injectarea în shell). */
        internal fun isValidPackageName(name: String): Boolean = name.matches(PACKAGE_NAME_REGEX)

        /** Oprim restaurarea după [MAX_CONSECUTIVE_RESTORE_FAILURES] eșecuri consecutive. */
        internal fun shouldStopRestore(consecutiveFailures: Int): Boolean =
            consecutiveFailures >= MAX_CONSECUTIVE_RESTORE_FAILURES

        /**
         * Rezolvă o intrare de arhivă în interiorul [root], respingând căile care
         * evadează (zip-slip, ex. `../evil`). Publică pentru testare pe JVM.
         */
        internal fun safeArchiveFile(root: File, entryName: String): File {
            val file = File(root, entryName)
            val rootPath = root.canonicalPath + File.separator
            require(file.canonicalPath.startsWith(rootPath)) { "Invalid file path in backup" }
            return file
        }

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
            return createBackup(
                dadb, workDir,
                openOutput = {
                    requireNotNull(context.contentResolver.openOutputStream(destination, "w")) {
                        "Could not open the selected backup file"
                    }
                },
                selectedPackages = selectedPackages,
                onProgress = onProgress
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    internal suspend fun createBackup(
        dadb: Dadb,
        workDir: File,
        openOutput: () -> OutputStream,
        selectedPackages: Set<String>? = null,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): PortalBackupSummary {
        currentCoroutineContext().ensureActive()
        val packages = listThirdPartyPackages(dadb).let { installed ->
            selectedPackages?.let { selected ->
                installed.filter { it in selected }
            } ?: installed
        }
        val appRecords = packages.map { packageName ->
            currentCoroutineContext().ensureActive()
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
            currentCoroutineContext().ensureActive()
            val packageDir = File(workDir, "apps/$packageName").apply { mkdirs() }
            val filesJson = JSONArray()

            remotePaths.forEachIndexed { fileIndex, remotePath ->
                currentCoroutineContext().ensureActive()
                val remoteName = remotePath.substringAfterLast('/').ifBlank { "app.apk" }
                val localName = "${fileIndex}_${sanitizeFileName(remoteName)}"
                val localFile = File(packageDir, localName)
                dadb.pull(localFile, remotePath)
                currentCoroutineContext().ensureActive()
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
        currentCoroutineContext().ensureActive()

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

        currentCoroutineContext().ensureActive()
        val output = openOutput()
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
                            currentCoroutineContext().ensureActive()
                            zip.putNextEntry(ZipEntry("apps/$packageName/${apk.name}"))
                            apk.inputStream().buffered().use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    zip.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                        }
                }
            }
        }

        return PortalBackupSummary(appRecords.size, settings.size)
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
            return restoreBackup(
                dadb, workDir,
                readPackageName = { readArchivePackageName(context, it) },
                stoppedReason = { failures, reason ->
                    context.getString(R.string.backup_restore_stopped_reason, failures, reason)
                },
                onProgress = onProgress
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    internal suspend fun restoreBackup(
        dadb: Dadb,
        workDir: File,
        readPackageName: (File) -> String?,
        stoppedReason: (Int, String) -> String,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): PortalRestoreSummary {
        currentCoroutineContext().ensureActive()
        val manifestFile = File(workDir, MANIFEST_ENTRY)
        require(manifestFile.isFile) { "The backup manifest is missing" }
        val manifest = JSONObject(manifestFile.readText())
        require(isSupportedFormat(manifest.optInt("formatVersion"))) {
            "Unsupported backup format"
        }

        val apps = manifest.optJSONArray("apps")
            ?: error("Backup manifest has no apps section")
        require(apps.length() <= MAX_ARCHIVE_ENTRIES) { "Backup contains too many apps" }
        val seenPackages = mutableSetOf<String>()
        // Validate the whole archive before any install or setting write reaches the device.
        val plan = (0 until apps.length()).map { index ->
            currentCoroutineContext().ensureActive()
            val app = apps.getJSONObject(index)
            val packageName = app.getString("packageName")
            require(isValidPackageName(packageName)) { "Invalid package name in backup" }
            require(seenPackages.add(packageName)) { "Duplicate package in backup: $packageName" }
            val files = app.optJSONArray("files")
                ?: error("Backup has no APK list for $packageName")
            require(files.length() in 1..MAX_ARCHIVE_ENTRIES) { "Invalid APK list for $packageName" }
            val packageDir = safeArchiveFile(workDir, "apps/$packageName")
            val seenFiles = mutableSetOf<String>()
            val apks = (0 until files.length()).map { fileIndex ->
                currentCoroutineContext().ensureActive()
                val fileName = files.getString(fileIndex)
                require(fileName.isNotBlank() && fileName == File(fileName).name &&
                    fileName != "." && fileName != ".." && !fileName.contains('\\')) {
                    "Invalid APK path for $packageName"
                }
                require(seenFiles.add(fileName)) { "Duplicate APK for $packageName" }
                val apk = safeArchiveFile(packageDir, fileName)
                require(apk.isFile && apk.length() > 0) { "Missing or empty APK for $packageName: $fileName" }
                require(readPackageName(apk) == packageName) {
                    "APK ${apk.name} package does not match $packageName"
                }
                apk
            }
            packageName to apks
        }

        var installedCount = 0
        val failedPackages = mutableListOf<String>()
        var consecutiveFailures = 0
        var stoppedEarlyReason: String? = null
        for ((index, app) in plan.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (packageName, apks) = app
            onProgress(index + 1, plan.size, packageName)
            currentCoroutineContext().ensureActive()
            val installResult = try {
                installPackage(dadb, apks)
                currentCoroutineContext().ensureActive()
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (installResult.isSuccess) {
                installedCount++
                consecutiveFailures = 0
            } else {
                failedPackages += packageName
                consecutiveFailures++
                if (shouldStopRestore(consecutiveFailures)) {
                    val reason = installResult.exceptionOrNull()?.message
                        ?: "Multiple restore operations failed"
                    stoppedEarlyReason = stoppedReason(consecutiveFailures, reason)
                    break
                }
            }
        }
        onProgress(installedCount + failedPackages.size, plan.size, "")
        currentCoroutineContext().ensureActive()
        val settingsResult = if (failedPackages.isEmpty() && stoppedEarlyReason == null) {
            restoreSettings(
                dadb,
                manifest.optJSONObject("settings") ?: JSONObject()
            )
        } else {
            SettingsRestoreResult(0, emptyList())
        }
        return PortalRestoreSummary(
            installedCount = installedCount,
            failedPackages = failedPackages,
            restoredSettingCount = settingsResult.restoredCount,
            stoppedEarlyReason = stoppedEarlyReason,
            failedSettings = settingsResult.failedSettings
        )
    }

    fun inspectBackup(
        context: Context,
        source: Uri,
        checkCancelled: () -> Unit = {}
    ): PortalBackupPreview {
        checkCancelled()
        val input = requireNotNull(context.contentResolver.openInputStream(source)) {
            "Could not open the selected backup"
        }
        return inspectBackup(input, checkCancelled)
    }

    internal fun inspectBackup(
        input: InputStream,
        checkCancelled: () -> Unit = {},
        maxArchiveBytes: Long = MAX_EXTRACTED_BYTES
    ): PortalBackupPreview {
        var preview: PortalBackupPreview? = null
        input.use { stream ->
            require(maxArchiveBytes in 0..MAX_EXTRACTED_BYTES) {
                "Invalid backup inspection byte limit"
            }
            ZipInputStream(stream.buffered()).use { zip ->
                var entryCount = 0
                var archiveBytes = 0L
                var manifestFound = false
                val seenEntries = mutableSetOf<String>()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled()
                    val entry = zip.nextEntry ?: break
                    checkCancelled()
                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES) {
                        "Backup contains too many files"
                    }
                    val isManifest = !entry.isDirectory && entry.name == MANIFEST_ENTRY
                    if (isManifest) {
                        require(!manifestFound) { "Backup contains multiple manifests" }
                        manifestFound = true
                    }
                    require(seenEntries.add(entry.name)) { "Duplicate file path in backup" }
                    require(entry.size < 0 || entry.size <= maxArchiveBytes - archiveBytes) {
                        "Backup is larger than the supported limit"
                    }
                    if (isManifest) {
                        require(entry.size < 0 || entry.size <= MAX_MANIFEST_BYTES) {
                            "Backup manifest is too large"
                        }
                    }
                    val bytes = if (isManifest) ByteArrayOutputStream() else null
                    var manifestBytes = 0L
                    // Read every entry, including directory payloads, to EOF here.
                    // closeEntry() would otherwise inflate skipped bytes without
                    // enforcing either the budget or cancellation checkpoints.
                    while (true) {
                        val archiveReadLimit = maxArchiveBytes - archiveBytes + 1
                        val manifestReadLimit = if (isManifest) {
                            MAX_MANIFEST_BYTES - manifestBytes + 1
                        } else {
                            archiveReadLimit
                        }
                        // The validated limits keep remaining + 1 within Long;
                        // probing one extra byte distinguishes EOF at the limit.
                        val readLimit = minOf(
                            buffer.size.toLong(), archiveReadLimit, manifestReadLimit
                        ).toInt()
                        checkCancelled()
                        val read = zip.read(buffer, 0, readLimit)
                        checkCancelled()
                        if (read < 0) break
                        archiveBytes += read
                        require(archiveBytes <= maxArchiveBytes) {
                            "Backup is larger than the supported limit"
                        }
                        if (isManifest) {
                            manifestBytes += read
                            require(manifestBytes <= MAX_MANIFEST_BYTES) {
                                "Backup manifest is too large"
                            }
                            bytes!!.write(buffer, 0, read)
                        }
                    }
                    if (isManifest) {
                        val manifest = JSONObject(bytes!!.toString(Charsets.UTF_8.name()))
                        require(isSupportedFormat(manifest.optInt("formatVersion"))) {
                            "Unsupported backup format"
                        }
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
                }
            }
        }
        checkCancelled()
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

    private data class SettingsRestoreResult(val restoredCount: Int, val failedSettings: List<String>)

    private suspend fun restoreSettings(dadb: Dadb, settings: JSONObject): SettingsRestoreResult {
        var restored = 0
        val failed = mutableListOf<String>()
        BACKUP_SETTINGS.forEach { (namespace, name) ->
            currentCoroutineContext().ensureActive()
            val value = settings.optString("$namespace.$name")
            if (value.isNotBlank() && isSafeSettingValue(value)) {
                try {
                    val response = dadb.shell("settings put $namespace $name ${shellQuote(value)}")
                    if (response.exitCode == 0) {
                        restored++
                    } else {
                        failed += "$namespace.$name: ${response.allOutput.trim().ifBlank { "Portal rejected the setting" }}"
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failed += "$namespace.$name: ${error.message ?: "Setting write failed"}"
                }
            }
        }
        return SettingsRestoreResult(restored, failed)
    }

    private suspend fun installPackage(dadb: Dadb, apks: List<File>) {
        currentCoroutineContext().ensureActive()
        if (apks.size > 1) {
            dadb.installMultiple(apks, "-r")
            return
        }

        val apk = apks.single()
        val remotePath = "/data/local/tmp/portal_restore_${UUID.randomUUID()}.apk"
        try {
            dadb.push(apk, remotePath)
            currentCoroutineContext().ensureActive()
            val response = dadb.shell("pm install -r ${shellQuote(remotePath)}")
            require(response.exitCode == 0 && response.allOutput.trim().equals("Success", ignoreCase = true)) {
                response.allOutput.trim().ifBlank { "Portal did not confirm APK installation" }
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
        extractArchive(input, destination)
    }

    internal fun extractArchive(input: InputStream, destination: File) {
        input.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                var entryCount = 0
                var extractedBytes = 0L
                var manifestFound = false
                val seenPaths = mutableSetOf<String>()
                var entry = zip.nextEntry
                while (entry != null) {
                    entryCount++
                    require(entryCount <= MAX_ARCHIVE_ENTRIES) { "Backup contains too many files" }
                    val outputFile = safeArchiveFile(destination, entry.name)
                    require(seenPaths.add(outputFile.canonicalPath)) { "Duplicate file path in backup" }
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
}
