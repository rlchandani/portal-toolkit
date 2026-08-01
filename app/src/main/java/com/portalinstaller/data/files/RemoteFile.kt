package com.portalinstaller.data.files

/** Un element remote listat prin `ls -la`. */
data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val modifiedAt: String,
    val permissions: String
)
