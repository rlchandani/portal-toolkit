/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.update

/** Everything the Portal Toolkit updater says to people, in one place. */
object OtaCopy {
    const val APP = "Portal Toolkit"
    const val BAD_MANIFEST = "The update information was incomplete. Try again later."
    const val UNKNOWN_SCHEMA = "This update needs a newer Portal Toolkit. Install it once from the release page."
    const val BAD_SOURCE = "The update didn't come from Portal Toolkit's releases: not installed."
    const val WRONG_APP = "The update is for a different app: not installed."
    const val NEEDS_NEWER_ANDROID = "This update needs a newer version of Android."
    const val SIZE_MISMATCH = "The download was incomplete: not installed. Try again."
    const val HASH_MISMATCH = "The download didn't match its fingerprint: not installed."
    const val UNREADABLE_APK = "The download isn't a valid app: not installed."
    const val VERSION_MISMATCH = "The download is a different version than announced: not installed."
    const val WRONG_SIGNER = "The download isn't signed by Portal Toolkit's key: not installed."
    const val TOO_LARGE = "The download is larger than announced: not installed."
    const val OFFLINE = "Couldn't reach GitHub. Check your connection and try again."
    const val NO_RELEASE = "No Portal Toolkit release has been published yet."
    const val ALLOW_SOURCE = "Switch on \"Allow from this source\", then come back: the update carries on."
    const val CANCELLED = "Update cancelled."
    const val CONFLICT = "This copy of Portal Toolkit can't be updated in place. Install it once from the release page."
    const val STORAGE = "Not enough space to install the update."
    const val INSTALL_FAILED = "Couldn't install the update."

    fun httpError(status: Int) = "GitHub answered HTTP $status. Try again later."
    fun available(versionName: String) = "$APP $versionName is available"
    fun installing(versionName: String) = "Updating $APP to $versionName…"
}
