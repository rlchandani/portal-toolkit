/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.companion

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Request diagnostics intentionally exclude credentials and bodies (PIN/source passwords). */
class CompanionRequest(
    val method: String, val url: String, internal val sessionToken: String? = null, val body: String? = null,
    val upload: CompanionUpload? = null,
    val onUploadProgress: ((Long) -> Unit)? = null,
    val binary: Boolean = false,
    val maxResponseBytes: Long = if (binary) 512L * 1024 else 4L * 1024 * 1024,
) {
    override fun toString() = "CompanionRequest(method=$method, credentials=redacted, body=redacted)"
}

data class CompanionResponse(val status: Int, val body: String, val bytes: ByteArray? = null) {
    override fun toString() = "CompanionResponse(status=$status, body=redacted)"
}

fun interface CompanionTransport {
    suspend fun execute(request: CompanionRequest): CompanionResponse
}

class CompanionException(val error: CompanionError) : IOException(error.message)

class OkHttpCompanionTransport(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CompanionTransport {
    override suspend fun execute(request: CompanionRequest): CompanionResponse = withContext(io) {
        val builder = Request.Builder().url(request.url).header("Accept", if (request.binary) "image/*" else "application/json")
        request.sessionToken?.let { builder.header("Authorization", "Bearer $it") }
        val upload = request.upload
        val outgoing = if (upload == null) request.body?.toRequestBody("application/json; charset=utf-8".toMediaType())
        else object : RequestBody() {
            override fun contentType() = upload.contentType.toMediaType()
            override fun contentLength() = upload.length
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                upload.open().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var sent = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        sent += count
                        if (sent > upload.length) throw IOException("Image size changed during upload.")
                        sink.write(buffer, 0, count)
                        request.onUploadProgress?.invoke(sent)
                    }
                    if (sent != upload.length) throw IOException("Image ended before its advertised size.")
                }
            }
        }
        builder.method(request.method, outgoing)
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    try {
                        val result = response.use {
                            val body = it.body ?: throw IOException("Portal returned an empty response.")
                            val source = body.source()
                            val limit = request.maxResponseBytes.coerceIn(1, MAX_RESPONSE_BYTES)
                            if (body.contentLength() > limit || source.request(limit + 1)) {
                                throw IOException("Portal response exceeds the configured limit.")
                            }
                            if (request.binary && it.isSuccessful) CompanionResponse(it.code, "", source.readByteArray())
                            else CompanionResponse(it.code, source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }
    companion object { private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024 }
}

class CompanionClient(private val transport: CompanionTransport = OkHttpCompanionTransport()) {
    suspend fun pair(endpoint: String, pin: String): JSONObject {
        require(pin.matches(Regex("[0-9]{6}"))) { "Enter the six-digit PIN shown on the Portal." }
        val result = request(endpoint, null, "pair", JSONObject().put("pin", pin))
        if (result.optString("token").isBlank()) throw CompanionException(CompanionError("missing_token", "Portal did not return a pairing session."))
        return result
    }

    suspend fun get(session: CompanionSession, route: String): JSONObject = request(session.device.endpoint, session.token, route)
    suspend fun post(session: CompanionSession, route: String, body: JSONObject): JSONObject =
        if (route == "settings") postSettings(session, body)
        else if (route == "text") postText(session, body)
        else request(session.device.endpoint, session.token, route, body)

    private suspend fun postText(session: CompanionSession, body: JSONObject): JSONObject {
        val response = try {
            transport.execute(CompanionRequest("POST", "${session.device.endpoint}/remote/text",
                session.token, body.toString()))
        } catch (error: IOException) {
            if (error is CompanionException) throw error
            throw textUnconfirmed()
        }
        if (response.status in 200..299) {
            val json = runCatching { JSONObject(response.body) }.getOrNull()
            val success = json?.opt("ok")
            if (json == null || success != true && success != false ||
                success == true && json.opt("applied") !is Boolean) {
                throw textUnconfirmed(response.status)
            }
        }
        return executeResponse(response)
    }

    private fun textUnconfirmed(status: Int? = null) = CompanionException(
        CompanionError("text_unconfirmed",
            "Text sending is unconfirmed. Review the Portal before sending again.", status))

    private suspend fun postSettings(session: CompanionSession, body: JSONObject): JSONObject {
        val domain = body.opt("domain") as? String
        val response = try {
            transport.execute(CompanionRequest("POST", "${session.device.endpoint}/remote/settings",
                session.token, body.toString()))
        } catch (error: IOException) {
            if (error is CompanionException) throw error
            throw settingsUnconfirmed()
        }
        if (response.status in 200..299) {
            val json = runCatching { JSONObject(response.body) }.getOrNull()
            val success = json?.opt("ok")
            val applied = json?.optJSONArray("applied")
            if (json == null || success != true && success != false ||
                success == true && (domain.isNullOrBlank() || json.optJSONObject("domain")?.opt("id") != domain ||
                    applied == null || (0 until applied.length()).any { applied.opt(it) !is String })) {
                throw settingsUnconfirmed(response.status)
            }
        }
        return executeResponse(response)
    }

    private fun settingsUnconfirmed(status: Int? = null) = CompanionException(
        CompanionError("settings_unconfirmed",
            "Settings completion is unconfirmed. Review this Portal's settings before applying again.", status))

    /** Validation needs hidden controls; ordinary settings reads remain visibility-filtered. */
    suspend fun settingsForValidation(session: CompanionSession, domain: String): JSONObject {
        require(domain.isNotBlank()) { "Choose a settings domain." }
        return executeJson(CompanionRequest("GET", photoUrl(session, "settings",
            mapOf("domain" to domain, "includeInactive" to "1")), session.token))
    }

    suspend fun photos(session: CompanionSession, folder: String, recursive: Boolean = false): JSONObject =
        photosExact(session, CompanionPhotos.folder(folder), recursive)

    internal suspend fun photosExact(session: CompanionSession, folder: String, recursive: Boolean = false): JSONObject =
        executeJson(CompanionRequest("GET", photoUrl(session, "photos", buildMap {
            put("path", CompanionPhotos.exactFolder(folder))
            if (recursive) put("recursive", "1")
        }), session.token))

    suspend fun deletePhoto(session: CompanionSession, path: String): JSONObject {
        val selectedPath = CompanionPhotos.photoPath(path)
        val response = transport.execute(CompanionRequest("DELETE",
            photoUrl(session, "photo", mapOf("path" to selectedPath)), session.token))
        if (response.status in 200..299) {
            val json = runCatching { JSONObject(response.body) }.getOrNull()
            val success = json?.opt("ok")
            if (json == null || success != true && success != false ||
                success == true && json.opt("path") != selectedPath) {
                throw CompanionException(CompanionError("delete_unconfirmed",
                    "The Portal did not confirm this deletion. Refresh and review your photos before retrying.",
                    response.status))
            }
        }
        return executeResponse(response)
    }

    suspend fun uploadPhoto(session: CompanionSession, folder: String, upload: CompanionUpload, progress: (Long) -> Unit): JSONObject {
        CompanionPhotos.safeName(upload.name)
        require(upload.length in 1..CompanionPhotos.MAX_IMAGE_BYTES) { "Images must be nonempty and at most 20 MiB. Choose a smaller image." }
        val destination = CompanionPhotos.exactFolder(folder)
        val response = try {
            transport.execute(CompanionRequest("POST", photoUrl(session, "photo-upload",
                mapOf("path" to destination, "name" to upload.name)), session.token,
                upload = upload, onUploadProgress = progress))
        } catch (error: IOException) {
            if (error is CompanionException) throw error
            throw uploadUnconfirmed()
        }
        if (response.status in 200..299) {
            val json = runCatching { JSONObject(response.body) }.getOrNull()
            val success = json?.opt("ok")
            val bytes = json?.opt("bytes")
            if (json == null || success != true && success != false ||
                success == true && (json.opt("path") != "$destination/${upload.name}" ||
                    bytes !is Number || bytes.toDouble() != upload.length.toDouble())) {
                throw uploadUnconfirmed(response.status)
            }
        }
        return executeResponse(response)
    }

    private fun uploadUnconfirmed(status: Int? = null) = CompanionException(
        CompanionError("upload_unconfirmed",
            "Upload completion is unconfirmed. Refresh this Portal's photos before retrying.", status))

    suspend fun photoThumbnail(session: CompanionSession, path: String): ByteArray = photoBytes(session, path)

    suspend fun photoBytes(session: CompanionSession, path: String, thumbnail: Boolean = true): ByteArray =
        imageBytes(session, "photo", buildMap {
            put("path", CompanionPhotos.photoPath(path))
            if (thumbnail) put("thumbnail", "1")
        }, if (thumbnail) 512L * 1024 else 8L * 1024 * 1024)

    suspend fun appIconBytes(session: CompanionSession, packageName: String): ByteArray {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) { "Invalid app package." }
        return imageBytes(session, "icon", mapOf("pkg" to packageName), 256L * 1024)
    }

    suspend fun mediaArtBytes(session: CompanionSession, version: String): ByteArray =
        imageBytes(session, "art", mapOf("v" to version), 512L * 1024)

    private suspend fun imageBytes(session: CompanionSession, route: String, query: Map<String, String>, limit: Long): ByteArray {
        val response = transport.execute(CompanionRequest("GET", photoUrl(session, route, query),
            session.token, binary = true, maxResponseBytes = limit))
        if (response.status !in 200..299) {
            executeResponse(response)
            throw CompanionException(CompanionError("thumbnail_failed", "Could not load the photo thumbnail.", response.status))
        }
        return response.bytes?.takeIf { it.size <= limit }
            ?: throw CompanionException(CompanionError("invalid_thumbnail", "Portal returned an invalid thumbnail."))
    }

    private fun photoUrl(session: CompanionSession, route: String, query: Map<String, String>): String =
        "${session.device.endpoint}/remote/$route".toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()

    private suspend fun request(endpoint: String, token: String?, route: String, body: JSONObject? = null): JSONObject {
        require(route.matches(Regex("[a-z]+(?:/[a-z]+)*"))) { "Invalid remote route." }
        return executeJson(CompanionRequest(if (body == null) "GET" else "POST", "$endpoint/remote/$route", token, body?.toString()))
    }

    private suspend fun executeJson(request: CompanionRequest): JSONObject = executeResponse(transport.execute(request))

    private fun executeResponse(response: CompanionResponse): JSONObject {
        val json = runCatching { JSONObject(response.body) }.getOrElse {
            throw CompanionException(CompanionError("invalid_response", "Portal returned invalid JSON (HTTP ${response.status}).", response.status))
        }
        if (response.status !in 200..299 || !json.optBoolean("ok")) {
            val code = json.optString("error", "request_failed")
            val message = when (code) {
                "bad_pin" -> "PIN was rejected or expired. Show a fresh PIN on the Portal."
                "unauthorized" -> "Pair this phone with the Portal again."
                "no_accessibility", "no_gestures" -> "Enable Immortal's accessibility service on the Portal."
                else -> "Portal rejected the request: $code (HTTP ${response.status})."
            }
            throw CompanionException(CompanionError(code, message, response.status, sanitized(json)))
        }
        if (json.has("dispatched") && !json.optBoolean("dispatched") || json.has("applied") && json.opt("applied") == false) {
            throw CompanionException(CompanionError("not_applied", "Portal could not apply this action. Check its focused app and accessibility service.", response.status, sanitized(json)))
        }
        return json
    }

    internal companion object {
        /** Server failure metadata is preserved; secret-shaped fields are never surfaced. */
        fun sanitized(json: JSONObject, sourceCredentials: Boolean = false): JSONObject {
            fun clean(value: Any?): Any? = when (value) {
                is JSONObject -> JSONObject().also { out -> value.keys().forEach { key ->
                    val lower = key.lowercase()
                    val configuredFlag = value.opt(key) is Boolean && (lower.startsWith("has") || lower.endsWith("configured"))
                    val secret = !configuredFlag && (lower.contains("token") || lower.contains("password") || lower.contains("secret") ||
                        lower in setOf("pin", "apikey", "immichkey", "smbpass", "davpass") ||
                        sourceCredentials && lower in setOf("key", "pass"))
                    if (!secret) out.put(key, clean(value.opt(key)))
                    else if (sourceCredentials) out.put("${key}Configured", value.opt(key)?.let { it != JSONObject.NULL && it.toString().isNotBlank() } == true)
                } }
                is JSONArray -> JSONArray().also { out -> (0 until value.length()).forEach { out.put(clean(value.opt(it))) } }
                else -> value
            }
            return clean(json) as JSONObject
        }
    }
}
