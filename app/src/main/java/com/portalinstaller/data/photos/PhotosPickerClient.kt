package com.portalinstaller.data.photos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** Rezultatul brut al unui apel HTTP: cod de stare + corp text. */
data class HttpResult(val code: Int, val body: String) {
    val isSuccess: Boolean get() = code in 200..299
}

/**
 * Seam HTTP injectabil, ca sa putem testa [PhotosPickerClient] pe JVM cu raspunsuri
 * predefinite, fara retea reala. Implementarea de productie e [OkHttpPickerHttp].
 */
interface PickerHttp {
    fun get(url: String, token: String): HttpResult
    fun post(url: String, token: String, jsonBody: String): HttpResult
    fun delete(url: String, token: String): HttpResult
    /** Descarca octetii de la [url] direct in [dest]. Arunca [PickerException] la eroare. */
    fun downloadTo(url: String, token: String, dest: File)
}

/** Eroare din API-ul de picker; [code] e codul HTTP (sau 0 pentru erori de retea). */
class PickerException(val code: Int, message: String) : Exception(message)

/**
 * Client pentru Google Photos Picker API. Fiecare metoda cere token-ul OAuth pentru
 * scope-ul `photospicker.mediaitems.readonly` si il trimite ca `Authorization: Bearer`.
 * Metodele arunca [PickerException] daca serverul raspunde cu non-2xx.
 */
class PhotosPickerClient(
    private val http: PickerHttp,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun createSession(token: String): PickerSession = withContext(ioDispatcher) {
        val res = http.post("${PickerApi.BASE_URL}/sessions", token, "{}")
        ensureSuccess(res, "create session")
        parseResponse("session") { PickerSession.parse(res.body) }
    }

    suspend fun getSession(token: String, sessionId: String): PickerSession = withContext(ioDispatcher) {
        val url = "${PickerApi.BASE_URL}/sessions".toHttpUrl().newBuilder().addPathSegment(sessionId).build()
        val res = http.get(url.toString(), token)
        ensureSuccess(res, "get session")
        parseResponse("session") { PickerSession.parse(res.body) }
    }

    suspend fun listMediaItems(
        token: String,
        sessionId: String,
        pageToken: String? = null,
        pageSize: Int = 100,
    ): MediaItemsPage = withContext(ioDispatcher) {
        val url = "${PickerApi.BASE_URL}/mediaItems".toHttpUrl().newBuilder()
            .addQueryParameter("sessionId", sessionId)
            .addQueryParameter("pageSize", pageSize.toString())
        if (!pageToken.isNullOrBlank()) url.addQueryParameter("pageToken", pageToken)
        val res = http.get(url.build().toString(), token)
        ensureSuccess(res, "list media items")
        parseResponse("media items") { MediaItemsPage.parse(res.body) }
    }

    /** Interogheaza toate paginile de poze alese si le concateneaza (sare peste videoclipuri). */
    suspend fun listAllPhotos(token: String, sessionId: String): List<PickedMediaItem> {
        val all = mutableListOf<PickedMediaItem>()
        val seenTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val page = listMediaItems(token, sessionId, pageToken)
            all += page.items
            pageToken = page.nextPageToken
            if (!pageToken.isNullOrBlank() && !seenTokens.add(pageToken)) {
                throw PickerException(0, "Google Photos returned a repeated page token")
            }
        } while (!pageToken.isNullOrBlank())
        return all.filter { it.isPhoto }
    }

    suspend fun deleteSession(token: String, sessionId: String) = withContext(ioDispatcher) {
        // Best-effort: nu vrem sa doboram fluxul daca stergerea esueaza.
        try {
            val url = "${PickerApi.BASE_URL}/sessions".toHttpUrl().newBuilder().addPathSegment(sessionId).build()
            http.delete(url.toString(), token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cleanup errors must not hide the import result.
        }
        Unit
    }

    /** Descarca octetii la rezolutie completa ai unei poze in [dest]. */
    suspend fun downloadPhoto(token: String, item: PickedMediaItem, dest: File) = withContext(ioDispatcher) {
        if (item.baseUrl.toHttpUrlOrNull()?.scheme != "https") {
            throw PickerException(0, "Google Photos returned an invalid photo URL")
        }
        http.downloadTo(PickerApi.downloadUrl(item.baseUrl), token, dest)
    }

    private fun <T> parseResponse(kind: String, parse: () -> T): T =
        try {
            parse()
        } catch (_: JSONException) {
            throw PickerException(0, "Google Photos returned invalid $kind data")
        }

    private fun ensureSuccess(res: HttpResult, action: String) {
        if (!res.isSuccess) {
            throw PickerException(res.code, "Google Photos $action failed (HTTP ${res.code})")
        }
    }
}

/** Implementarea de productie a [PickerHttp] peste OkHttp. */
class OkHttpPickerHttp(
    private val client: OkHttpClient = defaultClient(),
) : PickerHttp {

    override fun get(url: String, token: String): HttpResult = execute(
        Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
    )

    override fun post(url: String, token: String, jsonBody: String): HttpResult = execute(
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(jsonBody.toRequestBody(JSON))
            .build()
    )

    override fun delete(url: String, token: String): HttpResult = execute(
        Request.Builder().url(url).header("Authorization", "Bearer $token").delete().build()
    )

    override fun downloadTo(url: String, token: String, dest: File) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw PickerException(response.code, "photo download failed (HTTP ${response.code})")
            }
            val body = response.body ?: throw PickerException(response.code, "empty photo body")
            val type = body.contentType()
            val binary = type?.type == "application" && type.subtype == "octet-stream"
            if (type != null && type.type != "image" && !binary) {
                throw PickerException(response.code, "photo download did not contain an image")
            }
            val staged = File.createTempFile("picker-download-", ".part", dest.absoluteFile.parentFile)
            try {
                val count = staged.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                if (count == 0L || (body.contentLength() >= 0L && count != body.contentLength())) {
                    throw PickerException(response.code, "empty or incomplete photo body")
                }
                Files.move(
                    staged.toPath(), dest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                staged.delete()
            }
        }
    }

    private fun execute(request: Request): HttpResult =
        try {
            client.newCall(request).execute().use { response ->
                HttpResult(response.code, response.body?.string().orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PickerException(0, e.message ?: "network error")
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
