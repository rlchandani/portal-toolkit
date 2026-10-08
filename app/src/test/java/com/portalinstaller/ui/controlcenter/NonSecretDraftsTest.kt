/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NonSecretDraftsTest {
    @Test fun publicEndpointsRestoreWithoutChangingTheDraft() {
        listOf(
            "",
            "https://photos.example.invalid",
            "HTTP://photos.example.invalid/photos/",
            "https://photos.example.invalid:8443/dav/Family%20photos",
            "http://portal.local:2283/api",
            "http://localhost/photos",
            "http://192.0.2.10:8080/photos",
            "http://[2001:db8::1]:8080/photos",
            "webcal://calendar.example.invalid/public.ics",
            "https://photos.example.invalid/image%3Fname%23one.jpg",
        ).forEach { draft ->
            assertEquals(draft, draft, nonSecretUrlDraft(draft))
        }
    }

    @Test fun userInfoIsNeverSavedEvenWithoutAPassword() {
        listOf(
            "https://synthetic-user:synthetic-password@photos.example.invalid/dav",
            "https://synthetic-token@photos.example.invalid/dav",
            "https://@photos.example.invalid/dav",
            "https://synthetic%40user:synthetic%3Apassword@photos.example.invalid/dav",
        ).forEach { assertNull(it, nonSecretUrlDraft(it)) }
    }

    @Test fun everyQueryIsOmittedIncludingEmptyOrApparentlyPublicQueries() {
        listOf(
            "https://photos.example.invalid/image.jpg?X-Amz-Signature=synthetic-signature",
            "https://photos.example.invalid/dav?token=synthetic%2Ftoken",
            "https://photos.example.invalid/photos?size=large",
            "https://photos.example.invalid/photos?",
        ).forEach { assertNull(it, nonSecretUrlDraft(it)) }
    }

    @Test fun everyFragmentIsOmittedIncludingEmptyFragments() {
        listOf(
            "https://photos.example.invalid/photos#synthetic-token",
            "https://photos.example.invalid/photos#",
        ).forEach { assertNull(it, nonSecretUrlDraft(it)) }
    }

    @Test fun incompleteOpaqueAndMalformedUrlsAreNotSaved() {
        listOf(
            "photos.example.invalid/photos",
            "//photos.example.invalid/photos",
            "/photos",
            "https:photos.example.invalid/photos",
            "https:///photos",
            "file:///photos",
            "https://",
            "https://photos.example.invalid/%",
            "https://photos.example.invalid/%GG",
            "https://[2001:db8::1/photos",
            "https://photos.example.invalid:not-a-port/photos",
            "https://photos.example.invalid:65536/photos",
            "https://photos.example.invalid:-1/photos",
            "https://photos.example.invalid:/photos",
        ).forEach { assertNull(it, nonSecretUrlDraft(it)) }
    }

    @Test fun ambiguousAuthorityAndWhitespaceAreNotSaved() {
        listOf(
            "https://synthetic-user%40photos.example.invalid/photos",
            "https://photos_example.invalid/photos",
            "https://photos.example.invalid\\@other.example.invalid/photos",
            "https:\\\\photos.example.invalid\\photos",
            " https://photos.example.invalid/photos",
            "https://photos.example.invalid/photos ",
            "https://photos.example.invalid/Family photos",
            "https://photos.example.invalid/\nsynthetic-token",
            "https://photos.example.invalid/\tsynthetic-token",
            "https://photos.example.invalid/\u0000synthetic-token",
        ).forEach { assertNull(it, nonSecretUrlDraft(it)) }
    }
}
