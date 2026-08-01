package com.portalinstaller.data

/**
 * Regula de compatibilitate a instalării: dispozitivele Portal rulează Android 10
 * ([PortalDeviceConfig.MAX_SDK]), deci o aplicație al cărei minSdk depășește acest
 * plafon nu poate porni. Extrasă din AppsViewModel/InstalledAppsTab pentru a fi
 * testabilă pe JVM și pentru a avea o singură sursă de adevăr (vezi InstallPolicyTest).
 */
object InstallPolicy {

    /**
     * @param minSdk minSdk-ul aplicației; `null` când nu îl putem citi din APK/pachet.
     * @return `true` dacă aplicația poate rula pe Portal. `null` este permisiv:
     *   fără informație nu blocăm, lăsăm instalarea să decidă.
     */
    fun isCompatible(minSdk: Int?): Boolean {
        if (minSdk == null) return true
        return minSdk <= PortalDeviceConfig.MAX_SDK
    }
}
