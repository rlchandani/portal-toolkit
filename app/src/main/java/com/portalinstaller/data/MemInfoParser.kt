package com.portalinstaller.data

/**
 * Rezultatul parsarii `/proc/meminfo`: memoria totala si cea disponibila,
 * in bytes. [usedFraction] este gata de folosit pentru inelul din dashboard,
 * exact ca gauge-ul de stocare (folosit / total).
 */
data class MemInfo(
    val totalBytes: Long,
    val availableBytes: Long
) {
    /** Fractiunea de RAM folosita (0..1), clamp-uita. */
    val usedFraction: Float
        get() = if (totalBytes > 0) {
            ((totalBytes - availableBytes).toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Parser pur (fara dadb/timp real) pentru iesirea `cat /proc/meminfo`, ca sa
 * fie testabil determinist. Prefera `MemAvailable` (estimarea kernelului pentru
 * memoria efectiv utilizabila); daca lipseste, cade pe `MemFree`.
 */
object MemInfoParser {

    fun parse(output: String): MemInfo? {
        val total = readKb(output, "MemTotal") ?: return null
        val available = readKb(output, "MemAvailable")
            ?: readKb(output, "MemFree")
            ?: return null
        return MemInfo(
            totalBytes = total * 1024,
            availableBytes = available * 1024
        )
    }

    private fun readKb(output: String, key: String): Long? {
        val line = output.lineSequence().firstOrNull { it.trimStart().startsWith("$key:") }
            ?: return null
        return Regex("""\d+""").find(line)?.value?.toLongOrNull()
    }
}
