package com.portalinstaller.data.transfer

import okio.Buffer
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source

/** Numara octetii cititi dintr-un [Source] si raporteaza progresul incremental. */
fun Source.counting(total: Long, onProgress: (TransferProgress) -> Unit): Source =
    object : ForwardingSource(this) {
        private var transferred = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0L) {
                transferred += read
                onProgress(TransferProgress(transferred, total))
            }
            return read
        }
    }

/** Numara octetii scrisi intr-un [Sink] si raporteaza progresul incremental. */
fun Sink.counting(total: Long, onProgress: (TransferProgress) -> Unit): Sink =
    object : ForwardingSink(this) {
        private var transferred = 0L
        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            transferred += byteCount
            onProgress(TransferProgress(transferred, total))
        }
    }
