package com.portalinstaller.data.files

import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Operatii de fisiere pe Portal prin ADB. Toate ruleaza pe [Dispatchers.IO].
 * Directoarele se sterg doar cand sunt goale (fara `-r`, decizie de design v1).
 * Caile din afara /sdcard sunt permise doar in mod avansat ([PathSafety]).
 */
class PortalFileManager {

    suspend fun list(dadb: Dadb, path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        // -A include fisierele ascunse (fara . si ..). Verificam codul de iesire:
        // altfel un director inaccesibil (permisiune refuzata) ar parea gol.
        val resp = dadb.shell("ls -laA ${shellQuote(path)}")
        require(resp.exitCode == 0) { resp.allOutput.ifBlank { "ls a esuat pentru $path" } }
        LsParser.parse(path, resp.allOutput)
    }

    suspend fun delete(dadb: Dadb, file: RemoteFile, advancedMode: Boolean) = withContext(Dispatchers.IO) {
        PathSafety.requireAllowed(file.path, advancedMode)
        val cmd = if (file.isDirectory) "rmdir ${shellQuote(file.path)}" else "rm ${shellQuote(file.path)}"
        val resp = dadb.shell(cmd)
        require(resp.exitCode == 0) { resp.allOutput }
    }

    suspend fun rename(dadb: Dadb, file: RemoteFile, newName: String, advancedMode: Boolean) = withContext(Dispatchers.IO) {
        PathSafety.requireAllowed(file.path, advancedMode)
        val dest = "${file.path.substringBeforeLast('/')}/$newName"
        val resp = dadb.shell("mv ${shellQuote(file.path)} ${shellQuote(dest)}")
        require(resp.exitCode == 0) { resp.allOutput }
    }

    suspend fun move(dadb: Dadb, file: RemoteFile, destDir: String, advancedMode: Boolean) = withContext(Dispatchers.IO) {
        PathSafety.requireAllowed(file.path, advancedMode)
        PathSafety.requireAllowed(destDir, advancedMode)
        val dest = "${destDir.trimEnd('/')}/${file.name}"
        val resp = dadb.shell("mv ${shellQuote(file.path)} ${shellQuote(dest)}")
        require(resp.exitCode == 0) { resp.allOutput }
    }

    suspend fun mkdir(dadb: Dadb, parentPath: String, name: String, advancedMode: Boolean) = withContext(Dispatchers.IO) {
        val dest = "${parentPath.trimEnd('/')}/$name"
        PathSafety.requireAllowed(dest, advancedMode)
        val resp = dadb.shell("mkdir ${shellQuote(dest)}")
        require(resp.exitCode == 0) { resp.allOutput }
    }

    suspend fun pull(dadb: Dadb, file: RemoteFile, localDir: File, advancedMode: Boolean): File =
        withContext(Dispatchers.IO) {
            PathSafety.requireAllowed(file.path, advancedMode)
            val local = File(localDir, file.name)
            dadb.pull(local, file.path)
            local
        }

    suspend fun push(dadb: Dadb, localFile: File, destDir: String, advancedMode: Boolean): String =
        withContext(Dispatchers.IO) {
            PathSafety.requireAllowed(destDir, advancedMode)
            val remote = "${destDir.trimEnd('/')}/${localFile.name}"
            try {
                dadb.push(localFile, remote)
            } catch (e: Exception) {
                // Curatare push partial: sterge fisierul remote incomplet.
                runCatching { dadb.shell("rm ${shellQuote(remote)}") }
                throw e
            }
            remote
        }

    private fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"
}
