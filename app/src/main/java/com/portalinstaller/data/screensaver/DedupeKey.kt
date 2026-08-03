package com.portalinstaller.data.screensaver

/** Cheia de deduplicare a unei imagini: numele fisierului plus dimensiunea. */
data class DedupeKey(val name: String, val sizeBytes: Long)
