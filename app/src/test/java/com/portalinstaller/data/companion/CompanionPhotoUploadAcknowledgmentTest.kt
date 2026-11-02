/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionPhotoUploadAcknowledgmentTest {
    private val folder = "/sdcard/Album "
    private val name = "selected image.png"
    private val path = "$folder/$name"
    private val session = CompanionSession(CompanionDevice("a", "A", "http://a:8723"), "fixture-token")
    private fun ack() = JSONObject().put("ok", true).put("path", path).put("bytes", 77)
    private fun upload() = CompanionUpload(name, "image/png", 77, open = { ByteArrayInputStream(ByteArray(77)) })

    private suspend fun rejected(body: String, status: Int = 200): CompanionException {
        val calls = mutableListOf<CompanionRequest>()
        val client = CompanionClient(CompanionTransport {
            calls += it
            CompanionResponse(status, body)
        })
        val error = runCatching { client.uploadPhoto(session, folder, upload()) {} }.exceptionOrNull()
        assertTrue("Unconfirmed storage must not count as a completed upload", error is CompanionException)
        assertEquals("No upload replay", 1, calls.size)
        assertEquals("POST", calls.single().method)
        val url = calls.single().url.toHttpUrl()
        assertEquals(folder, url.queryParameter("path"))
        assertEquals(name, url.queryParameter("name"))
        assertEquals("fixture-token", calls.single().sessionToken)
        return error as CompanionException
    }

    @Test fun exactAcknowledgmentAcceptsNormalizedFolderAndPreservesWhitespace() = runTest {
        val client = CompanionClient(CompanionTransport {
            assertEquals(folder, it.url.toHttpUrl().queryParameter("path"))
            CompanionResponse(200, ack().toString())
        })
        assertEquals(path, client.uploadPhoto(session, "/storage/emulated/0/Album /", upload()) {}.getString("path"))
    }

    @Test fun differentDestinationCannotConfirmStorage() = runTest {
        assertEquals("upload_unconfirmed", rejected(ack().put("path", "/sdcard/Album/$name").toString()).error.code)
    }

    @Test fun missingDestinationCannotConfirmStorage() = runTest {
        assertEquals("upload_unconfirmed", rejected(ack().apply { remove("path") }.toString()).error.code)
    }

    @Test fun stringSuccessCannotConfirmStorage() = runTest {
        assertEquals("upload_unconfirmed", rejected(ack().put("ok", "true").toString()).error.code)
    }

    @Test fun missingSuccessCannotConfirmStorage() = runTest {
        assertEquals("upload_unconfirmed", rejected(ack().apply { remove("ok") }.toString()).error.code)
    }

    @Test fun stringByteCountCannotConfirmStorage() = runTest {
        assertEquals("upload_unconfirmed", rejected(ack().put("bytes", "77").toString()).error.code)
    }

    @Test fun fractionalByteCountCannotConfirmStorage() = runTest {
        assertEquals("upload_unconfirmed", rejected(ack().put("bytes", 77.25).toString()).error.code)
    }

    @Test fun missingByteCountCannotConfirmStorage() = runTest {
        assertEquals("upload_unconfirmed", rejected(ack().apply { remove("bytes") }.toString()).error.code)
    }

    @Test fun unreadablePositiveRepliesDoNotClaimRejectionOrSuccess() = runTest {
        for (body in listOf("", "{", "false", "0", "\"\""))
            assertEquals("upload_unconfirmed", rejected(body).error.code)
    }

    @Test fun realRejectionAndAuthenticationRemainServerErrors() = runTest {
        assertEquals("collision", rejected("""{"ok":false,"error":"collision"}""", 409).error.code)
        assertEquals("invalid_image", rejected("""{"ok":false,"error":"invalid_image"}""", 200).error.code)
        val denied = rejected("""{"ok":false,"error":"unauthorized"}""", 401)
        assertEquals("unauthorized", denied.error.code)
        assertEquals(401, denied.error.status)
        val malformed = rejected("{", 401)
        assertEquals("invalid_response", malformed.error.code)
        assertEquals(401, malformed.error.status)
    }

    @Test fun transportLossIsUnconfirmedAndNeverReplaysTheBody() = runTest {
        var sends = 0
        var closed = false
        val client = CompanionClient(CompanionTransport {
            sends++
            it.upload!!.open().use { input -> assertEquals(77, input.readBytes().size) }
            throw IOException("Fixture lost reply after body")
        })
        val input = CompanionUpload(name, "image/png", 77, open = {
            object : ByteArrayInputStream(ByteArray(77)) {
                override fun close() { closed = true; super.close() }
            }
        })
        val failure = runCatching { client.uploadPhoto(session, folder, input) {} }.exceptionOrNull()
        assertEquals("upload_unconfirmed", (failure as CompanionException).error.code)
        assertEquals(1, sends)
        assertTrue(closed)
    }

    @Test fun groupedUnconfirmedImageIsTerminalAndExcludedFromExplicitFailureRetry() = runTest {
        val sessions = listOf("a", "b").map {
            CompanionSession(CompanionDevice(it, it, "http://$it:8723", photoFolder = "/sdcard/$it"), "token-$it")
        }
        val store = object : CompanionSessionStore {
            override suspend fun load() = sessions
            override suspend fun save(sessions: List<CompanionSession>) = Unit
        }
        val sends = mutableListOf<String>()
        var disposed = 0
        val client = CompanionClient(CompanionTransport {
            val url = it.url.toHttpUrl()
            if (it.upload != null) {
                sends += url.host
                CompanionResponse(200, JSONObject().put("ok", true).put("bytes", 77)
                    .put("path", if (url.host == "a") "/sdcard/a/$name" else "/sdcard/other/$name").toString())
            } else CompanionResponse(200, JSONObject().put("ok", true).put("deviceId", url.host).toString())
        })
        val repository = CompanionRepository(client, store, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repository.ready.join()
        repository.uploadSelectedPhotos(listOf(PhotoUploadSource(name) {
            CompanionUpload(name, "image/png", 77, dispose = { disposed++ },
                open = { ByteArrayInputStream(ByteArray(77)) })
        })).join()
        val operation = repository.state.value.groupOperations.single()
        assertEquals(listOf("SUCCEEDED", "UNCONFIRMED"), operation.items.map { it.status.name })
        assertFalse(operation.running)
        assertFalse(operation.retryable)
        assertTrue(operation.items.last().detail!!.contains("unconfirmed"))
        assertEquals(2, disposed)
        repository.retryGroupOperation(operation.id).join()
        assertEquals(listOf("a", "b"), sends)
        repository.close()
    }

    @Test fun singleRoomUnconfirmedImageNeverIncrementsCompletedAndDisposesItsStream() = runTest {
        val store = object : CompanionSessionStore {
            override suspend fun load() = listOf(session)
            override suspend fun save(sessions: List<CompanionSession>) = Unit
        }
        var sends = 0
        var disposed = 0
        val client = CompanionClient(CompanionTransport {
            if (it.upload != null) {
                sends++
                CompanionResponse(200, """{"ok":true,"bytes":77,"path":"/sdcard/other.png"}""")
            } else if (it.url.toHttpUrl().encodedPath == "/remote/photos")
                CompanionResponse(200, """{"ok":true,"files":[]}""")
            else CompanionResponse(200, """{"ok":true,"deviceId":"a","np":{},"capabilities":{}}""")
        })
        val repository = CompanionRepository(client, store, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repository.ready.join()
        repository.selectDevice("a").join()
        repository.uploadPhotos(listOf(PhotoUploadSource(name) {
            CompanionUpload(name, "image/png", 77, dispose = { disposed++ },
                open = { ByteArrayInputStream(ByteArray(77)) })
        }), folder).join()
        val result = repository.state.value.photoTransfer!!
        assertEquals(0, result.completed)
        assertFalse(result.running)
        assertEquals("upload_unconfirmed", result.failures.single().error.code)
        assertTrue(repository.state.value.error!!.message.contains("need review"))
        assertEquals(1, sends)
        assertEquals(1, disposed)
        repository.close()
    }

    @Test fun mixedRetryResendsOnlyExplicitlyRejectedRoomAndRetainsUnconfirmedOutcome() = runTest {
        val sessions = listOf("a", "b", "c").map {
            CompanionSession(CompanionDevice(it, it, "http://$it:8723", photoFolder = "/sdcard/$it"), "token-$it")
        }
        val store = object : CompanionSessionStore {
            override suspend fun load() = sessions
            override suspend fun save(sessions: List<CompanionSession>) = Unit
        }
        val sends = mutableListOf<String>()
        var rejectC = true
        val client = CompanionClient(CompanionTransport {
            val url = it.url.toHttpUrl()
            if (it.upload == null)
                CompanionResponse(200, JSONObject().put("ok", true).put("deviceId", url.host).toString())
            else {
                sends += url.host
                if (url.host == "c" && rejectC) CompanionResponse(409, """{"ok":false,"error":"collision"}""")
                else CompanionResponse(200, JSONObject().put("ok", true).put("bytes", 77)
                    .put("path", if (url.host == "b") "/sdcard/other/$name" else "/sdcard/${url.host}/$name").toString())
            }
        })
        val repository = CompanionRepository(client, store, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repository.ready.join()
        repository.uploadSelectedPhotos(listOf(PhotoUploadSource(name) { upload() })).join()
        val operation = repository.state.value.groupOperations.single()
        assertEquals(listOf("SUCCEEDED", "UNCONFIRMED", "FAILED"), operation.items.map { it.status.name })
        assertTrue(operation.retryable)
        rejectC = false
        repository.retryGroupOperation(operation.id).join()
        assertEquals(listOf("a", "b", "c", "c"), sends)
        assertEquals(listOf("SUCCEEDED", "UNCONFIRMED", "SUCCEEDED"),
            repository.state.value.groupOperations.single().items.map { it.status.name })
        assertFalse(repository.state.value.groupOperations.single().retryable)
        repository.close()
    }

    @Test fun failedReadbackCannotReplaceTheSingleRoomUncertaintyWarning() = runTest {
        val store = object : CompanionSessionStore {
            override suspend fun load() = listOf(session)
            override suspend fun save(sessions: List<CompanionSession>) = Unit
        }
        var sends = 0
        var disposed = 0
        val client = CompanionClient(CompanionTransport {
            when {
                it.upload != null -> { sends++; throw IOException("Fixture reply lost") }
                it.url.toHttpUrl().encodedPath == "/remote/photos" -> throw IOException("Fixture refresh offline")
                else -> CompanionResponse(200, """{"ok":true,"deviceId":"a","np":{},"capabilities":{}}""")
            }
        })
        val repository = CompanionRepository(client, store, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
        repository.ready.join(); repository.selectDevice("a").join()
        repository.uploadPhotos(listOf(PhotoUploadSource(name) {
            CompanionUpload(name, "image/png", 77, dispose = { disposed++ },
                open = { ByteArrayInputStream(ByteArray(77)) })
        }), folder).join()
        assertEquals(0, repository.state.value.photoTransfer!!.completed)
        assertEquals("upload_unconfirmed", repository.state.value.photoTransfer!!.failures.single().error.code)
        assertTrue(repository.state.value.error!!.message.contains("unconfirmed"))
        assertEquals(CompanionConnection.OFFLINE, repository.state.value.connection)
        assertEquals(1, sends); assertEquals(1, disposed)
        repository.close()
    }

    @Test fun localCleanupFailureCannotReplaceConfirmedOrUnconfirmedGroupOutcome() = runTest {
        for (confirmed in listOf(true, false)) {
            val ownedScope = CoroutineScope(backgroundScope.coroutineContext +
                SupervisorJob(backgroundScope.coroutineContext[Job]))
            var sends = 0
            var disposed = 0
            val store = object : CompanionSessionStore {
                override suspend fun load() = listOf(session)
                override suspend fun save(sessions: List<CompanionSession>) = Unit
            }
            val client = CompanionClient(CompanionTransport {
                if (it.upload != null) {
                    sends++
                    CompanionResponse(200, ack().put("path",
                        if (confirmed) "${CompanionPhotos.DEFAULT_FOLDER}/$name" else "/sdcard/other.png").toString())
                } else CompanionResponse(200, """{"ok":true,"deviceId":"a"}""")
            })
            val repository = CompanionRepository(client, store, ownedScope,
                UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
            repository.ready.join()
            repository.uploadSelectedPhotos(listOf(PhotoUploadSource(name) {
                CompanionUpload(name, "image/png", 77, dispose = { disposed++; throw IOException("Fixture cleanup failed") },
                    open = { ByteArrayInputStream(ByteArray(77)) })
            })).join()
            val operation = repository.state.value.groupOperations.single()
            assertEquals(if (confirmed) "SUCCEEDED" else "UNCONFIRMED", operation.items.single().status.name)
            assertTrue(operation.items.single().detail!!.contains("cleanup"))
            assertFalse(operation.retryable)
            repository.retryGroupOperation(operation.id).join()
            assertEquals(1, sends); assertEquals(1, disposed)
            repository.close()
        }
    }

    @Test fun singleRoomCleanupFailureRetainsUploadOutcomeAndShowsLocalReview() = runTest {
        for (confirmed in listOf(true, false)) {
            val ownedScope = CoroutineScope(backgroundScope.coroutineContext +
                SupervisorJob(backgroundScope.coroutineContext[Job]))
            var sends = 0
            var disposed = 0
            val store = object : CompanionSessionStore {
                override suspend fun load() = listOf(session)
                override suspend fun save(sessions: List<CompanionSession>) = Unit
            }
            val client = CompanionClient(CompanionTransport {
                when {
                    it.upload != null -> {
                        sends++
                        CompanionResponse(200, ack().put("path", if (confirmed) path else "/sdcard/other.png").toString())
                    }
                    it.url.toHttpUrl().encodedPath == "/remote/photos" -> CompanionResponse(200, """{"ok":true,"files":[]}""")
                    else -> CompanionResponse(200, """{"ok":true,"deviceId":"a","np":{},"capabilities":{}}""")
                }
            })
            val repository = CompanionRepository(client, store, ownedScope,
                UnconfinedTestDispatcher(testScheduler), preloadManagement = false)
            repository.ready.join(); repository.selectDevice("a").join()
            repository.uploadPhotos(listOf(PhotoUploadSource(name) {
                CompanionUpload(name, "image/png", 77, dispose = { disposed++; throw IOException("Fixture cleanup failed") },
                    open = { ByteArrayInputStream(ByteArray(77)) })
            }), folder).join()
            val transfer = repository.state.value.photoTransfer!!
            assertEquals(if (confirmed) 1 else 0, transfer.completed)
            assertEquals(if (confirmed) "photo_cleanup_failed" else "upload_unconfirmed",
                transfer.failures.single().error.code)
            assertTrue(transfer.failures.single().error.message.contains("cleanup"))
            assertTrue(repository.state.value.error!!.message.contains("need review"))
            assertEquals(1, sends); assertEquals(1, disposed)
            repository.close()
        }
    }
}
