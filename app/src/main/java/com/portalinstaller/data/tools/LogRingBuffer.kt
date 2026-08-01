package com.portalinstaller.data.tools

/**
 * Buffer circular imutabil de linii de logcat. [append] intoarce o instanta
 * noua ce pastreaza cel mult [capacity] linii, cele mai vechi fiind evacuate.
 * Fiind imutabil, fiecare emisie catre UI este un obiect nou, deci Compose
 * reface compunerea de fiecare data (o lista mutata pe loc ar pastra aceeasi
 * referinta si actualizarea ar putea fi sarita).
 */
class LogRingBuffer private constructor(
    val capacity: Int,
    val lines: List<LogLine>
) {
    constructor(capacity: Int) : this(capacity, emptyList())

    fun append(line: LogLine): LogRingBuffer =
        LogRingBuffer(capacity, (lines + line).takeLast(capacity))
}
