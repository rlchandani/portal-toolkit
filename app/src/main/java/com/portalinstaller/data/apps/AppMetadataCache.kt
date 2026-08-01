package com.portalinstaller.data.apps

import java.io.File

/**
 * Cache pe disc pentru metadatele aplicatiilor de pe Portal (eticheta + iconita
 * PNG). Cheia combina numele pachetului cu codul versiunii, astfel incat o
 * actualizare a aplicatiei sa invalideze automat intrarea veche.
 *
 * Extragerea unei iconite necesita descarcarea `base.apk` (costisitor pe Wi-Fi);
 * cache-ul face ca acest cost sa fie platit o singura data per versiune.
 */
object AppMetadataCache {

    private const val DIR_NAME = "app_meta"

    fun keyFor(packageName: String, versionCode: Long): String = "$packageName@$versionCode"

    private fun dir(cacheDir: File): File = File(cacheDir, DIR_NAME).apply { mkdirs() }

    fun iconFile(cacheDir: File, key: String): File = File(dir(cacheDir), "$key.png")

    fun labelFile(cacheDir: File, key: String): File = File(dir(cacheDir), "$key.txt")

    fun isCached(cacheDir: File, key: String): Boolean =
        labelFile(cacheDir, key).exists() && iconFile(cacheDir, key).exists()
}
