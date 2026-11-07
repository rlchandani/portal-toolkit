/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionDevice
import com.portalinstaller.data.companion.CompanionRequest
import com.portalinstaller.data.companion.CompanionResponse
import com.portalinstaller.data.companion.CompanionSession
import com.portalinstaller.data.companion.CompanionTransport
import com.portalinstaller.data.companion.CompanionUpload
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSenderTest {
    private val folder = "/sdcard/Pictures/Immortal"
    private val session = CompanionSession(CompanionDevice("f", "Family Area", "http://f:8723", photoFolder = folder), "fixture-token")
    private val item = ShareItem("1", "a.jpg", 10)
    private val job = ShareJob("j1", ShareKind.PHOTOS, "Rohit's Pixel", 0, listOf(ShareRoom("f", "Family Area")), listOf(item))
    private val requests = mutableListOf<CompanionRequest>()

    private fun sender(network: PhoneNetwork = PhoneNetwork.WIFI, respond: (CompanionRequest) -> CompanionResponse) =
        ShareSender(CompanionClient(CompanionTransport { requests += it; respond(it) }),
            sessionFor = { if (it == "f") session else null },
            uploads = { _, i -> CompanionUpload(i.name, "image/jpeg", i.bytes) { ByteArray(i.bytes.toInt()).inputStream() } },
            network = { network })

    private fun ok(body: String) = CompanionResponse(200, body)
    private fun uploaded(req: CompanionRequest): CompanionResponse {
        val name = req.url.substringAfter("name=").substringBefore('&')
        return ok("""{"ok":true,"path":"$folder/$name","bytes":10}""")
    }

    @Test fun confirmedUploadMarksTheFreshPhotoWithTheSenderName() = runTest {
        val state = sender { if (it.url.contains("photo-upload")) uploaded(it) else ok("""{"ok":true,"id":"j1-1"}""") }
            .photo("f", job, item) {}
        assertEquals(ItemStatus.CONFIRMED, state.status)
        assertEquals("$folder/a.jpg", state.portalPath)
        val mark = JSONObject(requests.last().body!!)
        assertEquals("photo", mark.getString("type")); assertEquals("Rohit's Pixel", mark.getString("from"))
        assertEquals("$folder/a.jpg", mark.getString("path")); assertEquals(10, mark.getLong("bytes"))
    }

    @Test fun anOlderPortalWithoutTheShareRouteStillConfirmsTheSavedPhoto() = runTest {
        val state = sender { if (it.url.contains("photo-upload")) uploaded(it) else CompanionResponse(404, """{"ok":false,"error":"not_found"}""") }
            .photo("f", job, item) {}
        assertEquals(ItemStatus.CONFIRMED, state.status)
    }

    @Test fun aNameCollisionRetriesOnceUnderAUniqueName() = runTest {
        var first = true
        val state = sender {
            when {
                it.url.contains("photo-upload") && first -> { first = false; CompanionResponse(409, """{"ok":false,"error":"collision"}""") }
                it.url.contains("photo-upload") -> uploaded(it)
                else -> ok("""{"ok":true,"id":"j1-1"}""")
            }
        }.photo("f", job, item) {}
        assertEquals(ItemStatus.CONFIRMED, state.status)
        assertTrue(state.portalPath!!.startsWith("$folder/a-"))
        assertTrue(state.portalPath!!.endsWith(".jpg"))
    }

    @Test fun aDropMidUploadIsAFailureNamedWithItsProgress() = runTest {
        val state = sender {
            it.onUploadProgress?.invoke(5)
            throw IOException("reset")
        }.photo("f", job, item) {}
        assertEquals(ItemStatus.FAILED, state.status)
        assertEquals("The connection to Family Area dropped at 50%.", state.reason)
    }

    @Test fun losingTheReplyAfterEveryByteIsUnconfirmed() = runTest {
        val state = sender { it.onUploadProgress?.invoke(10); throw IOException("timeout") }.photo("f", job, item) {}
        assertEquals(ItemStatus.UNCONFIRMED, state.status)
    }

    @Test fun notesConfirmOnlyWhenThePortalEchoesTheId() = runTest {
        val note = ShareJob("n1", ShareKind.NOTE, "Rohit's Pixel", 0, job.rooms, listOf(ShareItem("note", "Note", 1)), text = "Milk")
        assertEquals(ItemState(ItemStatus.CONFIRMED, remoteState = "shown"),
            sender { ok("""{"ok":true,"id":"n1","state":"shown"}""") }.message("f", note))
        assertEquals(ItemStatus.UNCONFIRMED, sender { ok("""{"ok":true,"id":"other"}""") }.message("f", note).status)
        val body = JSONObject(requests.first().body!!)
        assertEquals("note", body.getString("type")); assertEquals("Milk", body.getString("text")); assertEquals("n1", body.getString("id"))
    }

    @Test fun checkAgainFindsTheFileInThePortalsListing() = runTest {
        val listing = """{"ok":true,"files":[{"name":"a.jpg","path":"$folder/a.jpg","size":10,"modified":1}]}"""
        val found = sender { ok(listing) }.check("f", job, item, ItemState(ItemStatus.UNCONFIRMED, 10))
        assertEquals(ItemStatus.CONFIRMED, found.status)
        val missing = sender { ok("""{"ok":true,"files":[]}""") }.check("f", job, item, ItemState(ItemStatus.UNCONFIRMED, 10))
        assertEquals(ItemState(ItemStatus.FAILED, 10, "Family Area has no record of it."), missing)
    }

    @Test fun awayFromWifiHoldsWithoutTouchingTheNetwork() = runTest {
        assertEquals(Reach.Held("Your phone isn’t on home Wi-Fi."), sender(PhoneNetwork.OTHER) { throw AssertionError() }.reach("f"))
        assertEquals(Reach.Held("Your phone is offline."), sender(PhoneNetwork.NONE) { throw AssertionError() }.reach("f"))
        assertTrue(requests.isEmpty())
    }

    @Test fun reachDistinguishesOfflineSharingOffAndReady() = runTest {
        assertEquals(Reach.Ready, sender { ok("""{"ok":true,"sharing":true}""") }.reach("f"))
        assertEquals(Reach.Refused("Sharing is turned off on Family Area."),
            sender { CompanionResponse(403, """{"ok":false,"error":"sharing_off"}""") }.reach("f"))
        assertEquals(Reach.Held("Family Area isn’t responding. It may be off or offline."), sender { throw IOException("no route") }.reach("f"))
        assertEquals(Reach.Refused(ShareSender.UNPAIRED), sender { ok("{}") }.reach("gone"))
    }
}
