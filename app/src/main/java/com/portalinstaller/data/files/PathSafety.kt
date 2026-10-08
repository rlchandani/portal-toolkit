package com.portalinstaller.data.files

/** Garda de siguranta: operatiile in afara /sdcard cer "mod avansat" (implicit oprit). */
object PathSafety {
    const val SDCARD_ROOT = "/sdcard"

    fun isWithinSdcard(path: String): Boolean {
        val normalized = normalize(path)
        return normalized == SDCARD_ROOT || normalized.startsWith("$SDCARD_ROOT/")
    }

    fun requireAllowed(path: String, advancedMode: Boolean) {
        normalize(path)
        if (!advancedMode && !isWithinSdcard(path)) {
            throw SecurityException("Operatie interzisa in afara /sdcard fara mod avansat: $path")
        }
    }

    /** Normalize Android absolute paths before checking a boundary or constructing a target. */
    fun normalize(path: String): String {
        require(path.startsWith('/') && path.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "Expected an absolute path without control characters"
        }
        val parts = ArrayDeque<String>()
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(it)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun requireName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\u0000' || it == '\n' || it == '\r' }) {
            "Expected a single file name"
        }
    }

    fun requireResolvedWithin(root: String, resolved: String) {
        val normalizedRoot = normalize(root).trimEnd('/')
        val normalizedPath = normalize(resolved)
        if (normalizedPath != normalizedRoot && !normalizedPath.startsWith("$normalizedRoot/")) {
            throw SecurityException("Path resolves outside shared storage")
        }
    }
}
