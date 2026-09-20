package com.portalinstaller.data.photos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
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
class PhotosPickerClient(private val http: PickerHttp) {

    suspend fun createSession(token: String): PickerSession = withContext(Dispatchers.IO) {
        val res = http.post("${PickerApi.BASE_URL}/sessions", token, "{}")
        ensureSuccess(res, "create session")
        PickerSession.parse(res.body)
    }

    suspend fun getSession(token: String, sessionId: String): PickerSession = withContext(Dispatchers.IO) {
        val res = http.get("${PickerApi.BASE_URL}/sessions/$sessionId", token)
        ensureSuccess(res, "get session")
        PickerSession.parse(res.body)
    }

    suspend fun listMediaItems(
        token: String,
        sessionId: String,
        pageToken: String? = null,
        pageSize: Int = 100,
    ): MediaItemsPage = withContext(Dispatchers.IO) {
        val sb = StringBuilder("${PickerApi.BASE_URL}/mediaItems?sessionId=$sessionId&pageSize=$pageSize")
        if (!pageToken.isNullOrBlank()) sb.append("&pageToken=").append(pageToken)
        val res = http.get(sb.toString(), token)
        ensureSuccess(res, "list media items")
        MediaItemsPage.parse(res.body)
    }

    /** Interogheaza toate paginile de poze alese si le concateneaza (sare peste videoclipuri). */
    suspend fun listAllPhotos(token: String, sessionId: String): List<PickedMediaItem> {
        val all = mutableListOf<PickedMediaItem>()
        var pageToken: String? = null
        do {
            val page = listMediaItems(token, sessionId, pageToken)
            all += page.items
            pageToken = page.nextPageToken
        } while (!pageToken.isNullOrBlank())
        return all.filter { it.isPhoto }
    }

    suspend fun deleteSession(token: String, sessionId: String) = withContext(Dispatchers.IO) {
        // Best-effort: nu vrem sa doboram fluxul daca stergerea esueaza.
        runCatching { http.delete("${PickerApi.BASE_URL}/sessions/$sessionId", token) }
        Unit
    }

    /** Descarca octetii la rezolutie completa ai unei poze in [dest]. */
    suspend fun downloadPhoto(token: String, item: PickedMediaItem, dest: File) = withContext(Dispatchers.IO) {
        http.downloadTo(PickerApi.downloadUrl(item.baseUrl), token, dest)
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
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    private fun execute(request: Request): HttpResult =
        try {
            client.newCall(request).execute().use { response ->
                HttpResult(response.code, response.body?.string().orEmpty())
            }
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
