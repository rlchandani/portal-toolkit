/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OkHttp for the call routes: no redirects, no retries, small bounded replies. */
class OkHttpCallHttp(
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
) : CallHttp {
    override suspend fun post(url: String, token: String, householdHeader: String, body: String, timeoutMs: Long): CallHttpResponse =
        withContext(Dispatchers.IO) {
            val client = base.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .header(CallSigner.HEADER, householdHeader)
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { res ->
                val source = res.body?.source() ?: throw IOException("empty reply")
                if (source.request(MAX_REPLY + 1)) throw IOException("reply too large")
                CallHttpResponse(res.code, source.readUtf8())
            }
        }

    companion object {
        private const val CONNECT_MS = 3_000L
        private const val MAX_REPLY = 64L * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
