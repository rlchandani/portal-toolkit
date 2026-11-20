/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import android.app.Application
import android.util.Log
import com.portalinstaller.data.companion.PrefsHouseholdKeySource
import com.portalinstaller.data.dinner.DinnerRepository
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject

/**
 * Video messages with the Portals (Calling 2b), all over the home Wi-Fi:
 * - Collect: a poll lists what a Portal left for us; we fetch it (`/remote/call/msg/get`, the
 *   reply's id and length signed over our nonce), open every chunk under the household transit
 *   key while re-sealing it under our rest key, then confirm (`/ack`) so the Portal deletes it.
 * - Send: `/remote/call/msg` with a `vmsg_put` header from our session's phone id, the take
 *   sealed chunk by chunk as it streams; success only for an ack signed over our nonce.
 * Plaintext exists only as our own take while composing, in private no-backup storage.
 */
object PhoneMessages {
    private const val TAG = "ToolkitMessages"
    const val PUT = "/remote/call/msg"
    const val GET = "/remote/call/msg/get"
    const val ACK = "/remote/call/msg/ack"
    private const val SIG_HEADER = "X-Immortal-Sig"
    private const val GET_DOMAIN = "vmsg_get"
    private const val ACK_DOMAIN = "vmsg_ack"
    private const val PUT_TYPE = "vmsg_put"
    private const val TRANSFER_MS = 120_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val oneAtATime = Mutex()
    private val random = SecureRandom()
    private lateinit var app: Application
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(TRANSFER_MS, TimeUnit.MILLISECONDS).followRedirects(false).retryOnConnectionFailure(false).build()
    }

    fun install(application: Application) {
        app = application
        scope.launch { runCatching { PhoneMessageStore.load(app) } }
    }

    /** A poll from [endpoint] listed [notices]: collect each new one, one at a time. */
    fun collect(endpoint: CallEndpoint, portalName: String, notices: List<MessageNotice>) {
        if (!::app.isInitialized) return
        scope.launch {
            oneAtATime.withLock {
                for (n in notices) {
                    if (!PhoneMessageStore.accepts(app, n)) { if (PhoneMessageStore.messages.value.any { it.id == n.id }) ack(endpoint, n.id); continue }
                    val m = runCatching { fetch(endpoint, n) }.onFailure { Log.w(TAG, "collect failed: ${it.javaClass.simpleName}") }.getOrNull()
                        ?: continue
                    ack(endpoint, n.id)
                    MessageAlerts.arrived(app, m)
                    Log.i(TAG, "message from ${portalName.take(2)}… collected")
                }
            }
        }
    }

    private fun fetch(endpoint: CallEndpoint, n: MessageNotice): PhoneMessage? {
        val key = PrefsHouseholdKeySource(app).peek() ?: return null
        val signer = CallSigner(key)
        val body = JSONObject().put("i", n.id).toString()
        val req = signer.sign(endpoint.token, GET, body)
        val call = http.newCall(request(endpoint, GET, req, body.toRequestBody(JSON)))
        call.execute().use { res ->
            if (res.code != HTTP_OK) throw IOException("get ${res.code}")
            val sig = res.header(SIG_HEADER) ?: throw IOException("unsigned")
            val expected = CallSigner.hmacHex(key, "$GET_DOMAIN|${req.nonce}|${n.id}|${n.sealedBytes}")
            if (!MessageDigest.isEqual(sig.toByteArray(), expected.toByteArray())) throw IOException("bad signature")
            val stream = res.body?.byteStream() ?: throw IOException("empty")
            if ((res.body?.contentLength() ?: -1L) != n.sealedBytes) throw IOException("length mismatch")
            return PhoneMessageStore.receive(app, key, n, endpoint.sessionId, stream, System.currentTimeMillis())
        }
    }

    private fun ack(endpoint: CallEndpoint, id: String) {
        val key = PrefsHouseholdKeySource(app).peek() ?: return
        val signer = CallSigner(key)
        val body = JSONObject().put("i", id).toString()
        val req = signer.sign(endpoint.token, ACK, body)
        runCatching {
            http.newCall(request(endpoint, ACK, req, body.toRequestBody(JSON))).execute().use { res ->
                val ok = res.code == HTTP_OK && signer.open(res.body?.string().orEmpty(), req.nonce, CallSigner.RESPONSE_DOMAIN)?.optString("i") == id
                if (!ok) Log.w(TAG, "ack not confirmed: ${res.code}")
            }
        }.onFailure { Log.w(TAG, "ack failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Sends [take] to [portal] (blocking; call off the main thread). Null on a confirmed delivery,
     * else a short reason ([PhoneMessageCopy.failure] words it).
     */
    suspend fun send(take: File, portal: PortalRef, durationMs: Long, audioOnly: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = PhoneCalls.endpoint(portal.sessionId) ?: return@runCatching "unreachable"
            val key = PrefsHouseholdKeySource(app).peek() ?: return@runCatching "no_household_key"
            val plain = take.length()
            if (plain <= 0 || plain > PhoneMessageLimits.MAX_BYTES) return@runCatching "too_large"
            val id = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val sealed = PhoneMessageCrypto.sealedLength(plain)
            val put = JSONObject().put("i", id).put("to", portal.portalId).put("fn", CallWire.cleanName(DinnerRepository.phoneName(app)) ?: "Phone")
                .put("ms", durationMs).put("z", sealed).put("c", PhoneMessageCrypto.chunkCount(plain)).put("a", audioOnly)
            val nonce = CallSigner.randomNonce()
            val json = JSONObject().put("t", PUT_TYPE).put("f", CallSigner.phoneId(endpoint.token)).put("ts", System.currentTimeMillis())
                .put("n", nonce).put("b", put).toString()
            val signed = "${CallSigner.MAGIC}|$json"
            val header = "$signed|${CallSigner.hmacHex(key, signed)}"
            val body = object : RequestBody() {
                override fun contentType() = OCTET
                override fun contentLength() = sealed
                override fun writeTo(sink: BufferedSink) {
                    BufferedInputStream(FileInputStream(take), PhoneMessageLimits.CHUNK).use { input ->
                        PhoneMessageCrypto.seal(PhoneMessageCrypto.transitKey(key, id), id, input, plain, sink.outputStream())
                    }
                }
            }
            val req = Request.Builder().url(endpoint.baseUrl.trimEnd('/') + PUT).header("Authorization", "Bearer ${endpoint.token}")
                .header(CallSigner.HEADER, header).post(body).build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (res.code != HTTP_OK) return@runCatching runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null } ?: "http_${res.code}"
                val payload = CallSigner(key).open(text, nonce, ACK_DOMAIN) ?: return@runCatching "bad_ack"
                if (!payload.optBoolean("ok") || payload.optString("i") != id) return@runCatching "bad_ack"
                Log.i(TAG, "message ${id.take(4)}… delivered (${sealed / 1024} KiB)")
                null
            }
        }.getOrElse { Log.w(TAG, "message send failed: ${it.javaClass.simpleName}"); "unreachable" }
    }

    private fun request(endpoint: CallEndpoint, path: String, req: SignedCallRequest, body: RequestBody) =
        Request.Builder().url(endpoint.baseUrl.trimEnd('/') + path).header("Authorization", "Bearer ${endpoint.token}")
            .header(CallSigner.HEADER, req.header).post(body).build()

    private const val HTTP_OK = 200
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val OCTET = "application/octet-stream".toMediaType()
}
