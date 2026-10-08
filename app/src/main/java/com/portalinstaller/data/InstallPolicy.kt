package com.portalinstaller.data

/**
 * Installation uses the connected device's API level and supported ABIs.
 * The historical ceiling remains a default for legacy UI callers; it is never
 * used by PortalConnectionManager's actual install boundary.
 */
object InstallPolicy {

    /**
     * @param minSdk minSdk-ul aplicației; `null` când nu îl putem citi din APK/pachet.
     * @return `true` dacă aplicația poate rula pe Portal. `null` este permisiv:
     *   fără informație nu blocăm, lăsăm instalarea să decidă.
     */
    fun isCompatible(
        minSdk: Int?,
        deviceSdk: Int? = PortalDeviceConfig.MAX_SDK,
        deviceAbis: List<String> = emptyList(),
        apkAbis: Set<String> = emptySet()
    ): Boolean {
        if (minSdk != null && deviceSdk != null && minSdk > deviceSdk) return false
        return apkAbis.isEmpty() || deviceAbis.isEmpty() || apkAbis.any { it in deviceAbis }
    }

    fun nativeAbis(apk: java.io.File): Set<String> =
        java.util.zip.ZipFile(apk).use { zip ->
            zip.entries().asSequence().mapNotNull { entry ->
                Regex("^lib/([^/]+)/[^/]+\\.so$").matchEntire(entry.name)?.groupValues?.get(1)
            }.toSet()
        }
}
