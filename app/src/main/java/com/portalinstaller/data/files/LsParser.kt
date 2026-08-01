package com.portalinstaller.data.files

/**
 * Parseaza iesirea toybox `ls -la` (stabil pe SDK 29) in [RemoteFile].
 * Coloane: perms links owner group size data ora nume. Numele poate contine
 * spatii, deci impartim in cel mult 8 campuri si tratam restul ca nume.
 */
object LsParser {
    fun parse(parentPath: String, lsOutput: String): List<RemoteFile> =
        lsOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("total ") }
            .mapNotNull { parseLine(parentPath, it) }
            .toList()

    private fun parseLine(parentPath: String, line: String): RemoteFile? {
        val parts = line.split(Regex("\\s+"), limit = 8)
        if (parts.size < 8) return null

        val perms = parts[0]
        val size = parts[4].toLongOrNull() ?: 0L
        val modifiedAt = "${parts[5]} ${parts[6]}"

        val isSymlink = perms.startsWith("l")
        var name = parts[7]
        if (isSymlink) name = name.substringBefore(" -> ")
        if (name == "." || name == "..") return null

        val isDirectory = perms.startsWith("d")
        val base = parentPath.trimEnd('/')
        return RemoteFile(
            name = name,
            path = "$base/$name",
            isDirectory = isDirectory,
            isSymlink = isSymlink,
            sizeBytes = size,
            modifiedAt = modifiedAt,
            permissions = perms
        )
    }
}
