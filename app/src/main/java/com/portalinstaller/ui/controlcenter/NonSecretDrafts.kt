/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import java.net.URI
import java.net.URISyntaxException

/**
 * Saved-state policy only: callers keep the original input in memory for editing and applying.
 * URLs with authentication components or uncertain parsing must not enter saved instance state.
 * Safe endpoint drafts are returned verbatim, including an intentionally cleared field.
 */
internal fun nonSecretUrlDraft(draft: String): String? {
    if (draft.isEmpty()) return draft
    if (draft.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    val url = try {
        URI(draft).parseServerAuthority()
    } catch (_: URISyntaxException) {
        return null
    }
    if (!url.isAbsolute || url.isOpaque || url.host.isNullOrEmpty() ||
        url.rawUserInfo != null || url.rawQuery != null || url.rawFragment != null ||
        url.rawAuthority.endsWith(":") || url.port != -1 && url.port !in 1..65535) return null
    return draft
}
