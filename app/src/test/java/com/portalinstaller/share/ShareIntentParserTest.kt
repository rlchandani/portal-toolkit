/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntentParserTest {
    private val send = ShareIntentParser.ACTION_SEND
    private val multiple = ShareIntentParser.ACTION_SEND_MULTIPLE

    @Test fun multipleImagesBecomeOnePhotoShare() {
        val parsed = ShareIntentParser.parse(multiple, "image/*", null, null, listOf("content://a/1", "content://a/2", "content://a/1"))
        assertEquals(ShareParse.Ok(SharePayload.Images(listOf("content://a/1", "content://a/2"))), parsed)
    }

    @Test fun nonContentStreamsAreIgnored() {
        assertTrue(ShareIntentParser.parse(send, "image/jpeg", null, null, listOf("http://evil/x.jpg")) is ShareParse.Rejected)
        // A file:// or Toolkit-owned URI could make Toolkit upload its own private files (pairing tokens).
        assertTrue(ShareIntentParser.parse(send, "image/jpeg", null, null,
            listOf("file:///data/data/com.portalinstaller/no_backup/companion/companion-sessions.json")) is ShareParse.Rejected)
        assertTrue(ShareIntentParser.parse(send, "image/jpeg", null, null, listOf("content://com.portalinstaller.files/x")) is ShareParse.Rejected)
    }

    @Test fun tooManyImagesAreRefusedWithTheLimit() {
        val uris = (1..51).map { "content://a/$it" }
        val parsed = ShareIntentParser.parse(multiple, "image/*", null, null, uris) as ShareParse.Rejected
        assertTrue(parsed.message.contains("50"))
    }

    @Test fun plainTextIsANote() {
        assertEquals(ShareParse.Ok(SharePayload.Note("Swim bag is by the door")),
            ShareIntentParser.parse(send, "text/plain", "  Swim bag is by the door \n", null, emptyList()))
    }

    @Test fun aLoneUrlIsALinkTitledBySubject() {
        assertEquals(ShareParse.Ok(SharePayload.Link("https://example.com/cake", "Birthday cake")),
            ShareIntentParser.parse(send, "text/plain", "https://example.com/cake", "Birthday cake", emptyList()))
    }

    @Test fun captionThenUrlIsALinkTitledByCaption() {
        assertEquals(SharePayload.Link("https://x.org/p", "Pasta recipe"), ShareIntentParser.link("Pasta recipe: https://x.org/p", null))
    }

    @Test fun textWithAUrlInTheMiddleStaysANote() {
        assertNull(ShareIntentParser.link("see https://x.org/p tonight", null))
        assertNull(ShareIntentParser.link("https://a.org and https://b.org", null))
    }

    @Test fun credentialUrlsAndOtherSchemesAreNotLinks() {
        assertNull(ShareIntentParser.link("https://user:pw@x.org/", null))
        assertEquals(false, ShareIntentParser.validUrl("javascript:alert(1)"))
    }

    @Test fun emptyOrOversizedTextIsRefused() {
        assertTrue(ShareIntentParser.parse(send, "text/plain", "   ", null, emptyList()) is ShareParse.Rejected)
        assertTrue(ShareIntentParser.parse(send, "text/plain", "x".repeat(1001), null, emptyList()) is ShareParse.Rejected)
    }

    @Test fun unknownActionsAndTypesAreRefused() {
        assertTrue(ShareIntentParser.parse("android.intent.action.VIEW", "image/*", null, null, listOf("content://a")) is ShareParse.Rejected)
        assertTrue(ShareIntentParser.parse(send, "video/mp4", null, null, listOf("content://a")) is ShareParse.Rejected)
    }
}
