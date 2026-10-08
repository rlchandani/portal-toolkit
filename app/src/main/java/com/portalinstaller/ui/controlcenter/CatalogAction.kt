/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import org.json.JSONObject

internal data class CatalogAction(val label: String, val enabled: Boolean, val operation: String)

internal fun catalogAction(app: JSONObject): CatalogAction {
    // The paired operation API deliberately rejects updating the launcher itself.
    if (app.optString("packageName") in setOf("com.immortal.launcher", "com.immortal.launcher.debug")) {
        return CatalogAction("Manage on Portal", enabled = false, operation = "update")
    }
    val installed = app.optBoolean("installed")
    val compatible = app.optBoolean("compatible")
    val available = app.opt("updateAvailable") as? Boolean
    return CatalogAction(
        label = if (!compatible) "Unavailable on this Portal"
            else if (installed && available == null) "Check for updates"
            else if (installed && available == false) "Up to date"
            else if (installed) "Update" else "Install",
        enabled = compatible && (!installed || available != false),
        operation = if (installed) "update" else "install",
    )
}
