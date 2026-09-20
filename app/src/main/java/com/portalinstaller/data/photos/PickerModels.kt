package com.portalinstaller.data.photos

import org.json.JSONObject

/**
 * Modele + parsare pentru Google Photos Picker API (session-based).
 *
 * Fluxul: cream o sesiune -> utilizatorul alege poze in UI-ul gazduit de Google ->
 * interogam sesiunea pana cand `mediaItemsSet` devine true -> listam pozele alese ->
 * descarcam fiecare `baseUrl`. Nu exista notiunea de "album" in API: primim doar
 * pozele individuale pe care utilizatorul le-a bifat (poate naviga intr-un album si
 * le poate bifa acolo). Vezi docs/superpowers/specs/2026-09-17-google-photos-picker-design.md.
 *
 * Parsarea foloseste org.json (inclus in Android) ca sa evitam o dependenta noua de
 * serializare si ca sa putem testa pe JVM cu dependenta de test `org.json:json`.
 */

/** Constante partajate de client si de parsare. */
object PickerApi {
    const val BASE_URL = "https://photospicker.googleapis.com/v1"
    const val SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

    /** Valori de rezerva daca serverul nu trimite `pollingConfig`. */
    const val DEFAULT_POLL_MILLIS = 3_000L
    const val DEFAULT_TIMEOUT_MILLIS = 300_000L

    /** Sufix obligatoriu pe baseUrl ca sa primim octetii la rezolutie completa. */
    fun downloadUrl(baseUrl: String): String = "$baseUrl=d"
}

/**
 * O sesiune de picker. `pickerUri` se deschide intr-un Custom Tab; `mediaItemsSet`
 * devine true dupa ce utilizatorul termina de ales.
 */
data class PickerSession(
    val id: String,
    val pickerUri: String,
    val mediaItemsSet: Boolean,
    val pollIntervalMillis: Long,
    val timeoutMillis: Long,
) {
    companion object {
        fun parse(json: String): PickerSession {
            val obj = JSONObject(json)
            val polling = obj.optJSONObject("pollingConfig")
            return PickerSession(
                id = obj.getString("id"),
                pickerUri = obj.optString("pickerUri", ""),
                mediaItemsSet = obj.optBoolean("mediaItemsSet", false),
                pollIntervalMillis = parseDurationMillis(
                    polling?.optString("pollInterval"), PickerApi.DEFAULT_POLL_MILLIS
                ),
                timeoutMillis = parseDurationMillis(
                    polling?.optString("timeoutIn"), PickerApi.DEFAULT_TIMEOUT_MILLIS
                ),
            )
        }
    }
}

/** O poza aleasa. Doar `PHOTO` ne intereseaza pentru screensaver (videoclipurile se sar). */
data class PickedMediaItem(
    val id: String,
    val type: String,
    val baseUrl: String,
    val mimeType: String,
    val filename: String,
) {
    val isPhoto: Boolean get() = type.equals("PHOTO", ignoreCase = true)
}

/** O pagina din lista de poze alese, cu token-ul de continuare (null la ultima pagina). */
data class MediaItemsPage(
    val items: List<PickedMediaItem>,
    val nextPageToken: String?,
) {
    companion object {
        fun parse(json: String): MediaItemsPage {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("mediaItems")
            val items = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val it = arr.getJSONObject(i)
                        val file = it.optJSONObject("mediaFile") ?: JSONObject()
                        val id = it.optString("id", "")
                        val filename = file.optString("filename").ifBlank { "photo_$id.jpg" }
                        add(
                            PickedMediaItem(
                                id = id,
                                type = it.optString("type", "PHOTO"),
                                baseUrl = file.optString("baseUrl", ""),
                                mimeType = file.optString("mimeType", "image/jpeg"),
                                filename = filename,
                            )
                        )
                    }
                }
            }
            val token = obj.optString("nextPageToken").ifBlank { null }
            return MediaItemsPage(items, token)
        }
    }
}

/**
 * Converteste un `google.protobuf.Duration` in reprezentare JSON (ex. "5s", "0.100s")
 * in milisecunde. Intoarce [fallback] daca sirul lipseste sau nu poate fi interpretat.
 */
internal fun parseDurationMillis(raw: String?, fallback: Long): Long {
    if (raw.isNullOrBlank()) return fallback
    val seconds = raw.removeSuffix("s").toDoubleOrNull() ?: return fallback
    val millis = (seconds * 1000.0).toLong()
    return if (millis > 0L) millis else fallback
}
