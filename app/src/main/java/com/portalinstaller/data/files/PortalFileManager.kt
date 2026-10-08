package com.portalinstaller.data.files

import com.portalinstaller.data.adb.shellQuote
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.portalinstaller.data.transfer.TransferProgress
import com.portalinstaller.data.transfer.counting
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** An upload's destination already exists; preserve it and report a skipped item. */
class DestinationExistsException(val path: String) : IOException("Upload destination already exists: $path")

/**
 * Operatii de fisiere pe Portal prin ADB. Toate ruleaza pe [Dispatchers.IO].
 * Directoarele se sterg doar cand sunt goale (fara `-r`, decizie de design v1).
 * Caile din afara /sdcard sunt permise doar in mod avansat ([PathSafety]).
 */
class PortalFileManager {

    suspend fun list(dadb: Dadb, path: String, advancedMode: Boolean = false): List<RemoteFile> = withContext(Dispatchers.IO) {
        requireResolvedAllowed(dadb, path, advancedMode)
        // -A include fisierele ascunse (fara . si ..). Verificam codul de iesire:
        // altfel un director inaccesibil (permisiune refuzata) ar parea gol.
        // Follow a symlinked parent (e.g. /sdcard) without dereferencing child symlinks.
        val directoryPath = path.trimEnd('/') + "/"
        val resp = dadb.shell("ls -laA ${shellQuote(directoryPath)}")
        require(resp.exitCode == 0) { resp.allOutput.ifBlank { "ls a esuat pentru $path" } }
        LsParser.parse(path, resp.allOutput)
    }

    suspend fun delete(dadb: Dadb, file: RemoteFile, advancedMode: Boolean) = withContext(Dispatchers.IO) {
        requireResolvedAllowed(dadb, file.path, advancedMode)
        val cmd = if (file.isDirectory) "rmdir ${shellQuote(file.path)}" else "rm ${shellQuote(file.path)}"
        val resp = dadb.shell(cmd)
        require(resp.exitCode == 0) { resp.allOutput }
    }

    suspend fun rename(dadb: Dadb, file: RemoteFile, newName: String, advancedMode: Boolean) = withContext(Dispatchers.IO) {
        PathSafety.requireName(newName)
        requireResolvedAllowed(dadb, file.path, advancedMode)
        val dest = RemotePaths.renameDest(file.path, newName)
        requireDestinationAllowed(dadb, dest, advancedMode)
        val resp = dadb.shell("mv -f ${shellQuote(file.path)} ${shellQuote(dest)}")
        require(resp.exitCode == 0) { resp.allOutput }
    }

    suspend fun move(dadb: Dadb, file: RemoteFile, destDir: String, advancedMode: Boolean) = withContext(Dispatchers.IO) {
        PathSafety.requireName(file.name)
        requireResolvedAllowed(dadb, file.path, advancedMode)
        requireResolvedAllowed(dadb, destDir, advancedMode)
        val dest = RemotePaths.moveDest(destDir, file.name)
        requireDestinationAllowed(dadb, dest, advancedMode)
        val resp = dadb.shell("mv ${shellQuote(file.path)} ${shellQuote(dest)}")
        require(resp.exitCode == 0) { resp.allOutput }
    }

    suspend fun mkdir(dadb: Dadb, parentPath: String, name: String, advancedMode: Boolean) = withContext(Dispatchers.IO) {
        PathSafety.requireName(name)
        val dest = RemotePaths.join(parentPath, name)
        requireDestinationAllowed(dadb, dest, advancedMode)
        val resp = dadb.shell("mkdir ${shellQuote(dest)}")
        require(resp.exitCode == 0) { resp.allOutput }
    }

    suspend fun pull(
        dadb: Dadb, file: RemoteFile, localDir: File, advancedMode: Boolean,
        onProgress: (TransferProgress) -> Unit = {}
    ): File =
        withContext(Dispatchers.IO) {
            PathSafety.requireName(file.name)
            requireResolvedAllowed(dadb, file.path, advancedMode)
            require(!file.isDirectory) { "Cannot download a directory" }
            val local = File(localDir, file.name)
            val staging = File.createTempFile("portal-download-", ".part", localDir)
            val job = currentCoroutineContext()
            try {
                staging.sink().counting(file.sizeBytes) {
                    job.ensureActive()
                    onProgress(it)
                }.use { dadb.pull(it, file.path) }
                job.ensureActive()
                require(file.sizeBytes < 0 || staging.length() == file.sizeBytes) { "Incomplete download" }
                java.nio.file.Files.move(
                    staging.toPath(), local.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                local
            } finally {
                staging.delete()
            }
        }

    suspend fun push(
        dadb: Dadb, localFile: File, destDir: String, advancedMode: Boolean,
        onProgress: (TransferProgress) -> Unit = {}
    ): String = push(dadb, localFile, destDir, advancedMode, replaceExisting = true, onProgress = onProgress)

    suspend fun push(
        dadb: Dadb, localFile: File, destDir: String, advancedMode: Boolean,
        replaceExisting: Boolean,
        onProgress: (TransferProgress) -> Unit = {}
    ): String =
        withContext(Dispatchers.IO) {
            PathSafety.requireName(localFile.name)
            require(localFile.isFile) { "Upload source is not a file" }
            val remote = RemotePaths.join(PathSafety.normalize(destDir), localFile.name)
            requireDestinationAllowed(dadb, remote, advancedMode)
            val staging = RemotePaths.join(destDir, ".${localFile.name}.${UUID.randomUUID()}.part")
            val size = localFile.length()
            val digest = MessageDigest.getInstance("SHA-256")
            localFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val expectedHash = digest.digest().joinToString("") { "%02x".format(it) }
            val job = currentCoroutineContext()
            try {
                localFile.source().counting(size) {
                    job.ensureActive()
                    onProgress(it)
                }.use { dadb.push(it, staging, 420, System.currentTimeMillis() / 1000) }
                job.ensureActive()
                val stat = dadb.shell("stat -c %s ${shellQuote(staging)}")
                require(stat.exitCode == 0 && stat.output.trim().toLongOrNull() == size) { "Incomplete upload" }
                val hash = dadb.shell("sha256sum ${shellQuote(staging)}")
                require(hash.exitCode == 0 && hash.output.trim().substringBefore(' ') == expectedHash) {
                    "Upload verification failed"
                }
                // Recheck the parent after a long transfer. mv replaces a file atomically
                // on the same filesystem, but would move INTO an existing directory.
                requireDestinationAllowed(dadb, remote, advancedMode)
                job.ensureActive()
                val destination = shellQuote(remote)
                val staged = shellQuote(staging)
                val rename = dadb.shell(
                    if (replaceExisting) {
                        "test ! -d $destination && mv -f $staged $destination"
                    } else {
                        "if test -e $destination || test -L $destination; then exit 73; fi; " +
                            "test ! -d $destination && mv -n $staged $destination"
                    }
                )
                if (!replaceExisting && rename.exitCode == 73) throw DestinationExistsException(remote)
                require(rename.exitCode == 0) { rename.allOutput.ifBlank { "Upload rename failed" } }
                if (!replaceExisting) {
                    // API 29 toybox mv -n exits zero on collision and retains the source.
                    // Its rename publishes a complete file, but its existence check and
                    // rename are separate syscalls on this Android version.
                    val published = dadb.shell(
                        "if test -e $staged || test -L $staged; then " +
                            "if test -e $destination || test -L $destination; then exit 73; else exit 1; fi; fi; " +
                            "test -f $destination && test ! -L $destination"
                    )
                    if (published.exitCode == 73) throw DestinationExistsException(remote)
                    require(published.exitCode == 0) { "Upload publication could not be confirmed" }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { dadb.shell("rm -f ${shellQuote(staging)}") }
                }
            }
            remote
        }

    private fun requireDestinationAllowed(dadb: Dadb, path: String, advancedMode: Boolean) {
        PathSafety.requireAllowed(path, advancedMode)
        requireResolvedAllowed(dadb, RemotePaths.parentOf(PathSafety.normalize(path)), advancedMode)
    }

    private fun requireResolvedAllowed(dadb: Dadb, path: String, advancedMode: Boolean) {
        PathSafety.requireAllowed(path, advancedMode)
        if (advancedMode) return
        fun resolve(target: String): String {
            val response = dadb.shell("readlink -f ${shellQuote(PathSafety.normalize(target))}")
            require(response.exitCode == 0 && response.output.trim().startsWith('/')) {
                "Cannot resolve remote path"
            }
            return response.output.trim()
        }
        PathSafety.requireResolvedWithin(resolve(PathSafety.SDCARD_ROOT), resolve(path))
    }
}
