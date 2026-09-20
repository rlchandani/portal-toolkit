package com.portalinstaller.data.photos

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Rezultatul unei cereri de autorizare Google.
 *  - [Token]: avem token-ul de acces, putem apela API-ul de picker.
 *  - [NeedsResolution]: Google cere consimtamantul utilizatorului; UI-ul trebuie sa
 *    lanseze [pendingIntent] si sa intoarca rezultatul prin [PhotosAuth.tokenFromResult].
 *  - [Failed]: autorizarea a esuat sau a fost anulata.
 */
sealed class AuthOutcome {
    data class Token(val accessToken: String) : AuthOutcome()
    data class NeedsResolution(val pendingIntent: PendingIntent) : AuthOutcome()
    data class Failed(val message: String) : AuthOutcome()
}

/**
 * Invelis subtire peste Google Identity `AuthorizationClient` pentru scope-ul de picker.
 *
 * Cerem doar un token de acces (~1h), nu acces offline: importul e o operatiune unica,
 * asa ca nu avem nevoie de refresh token (care oricum expira in 7 zile in modul Testing).
 * Aplicatia e potrivita cu proiectul Google Cloud dupa numele de pachet + SHA-1, deci nu
 * e nevoie de niciun client id in cod. Vezi ghidul de configurare din docs/.
 */
class PhotosAuth(private val context: Context) {

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(PickerApi.SCOPE)))
            .build()

    /** Cere autorizarea. Poate intoarce direct un token sau o rezolutie de lansat. */
    suspend fun authorize(): AuthOutcome = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                val outcome = when {
                    result.hasResolution() -> result.pendingIntent
                        ?.let { AuthOutcome.NeedsResolution(it) }
                        ?: AuthOutcome.Failed("Authorization needs consent but no intent was returned")
                    result.accessToken != null -> AuthOutcome.Token(result.accessToken!!)
                    else -> AuthOutcome.Failed("No access token was returned")
                }
                if (cont.isActive) cont.resume(outcome)
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resume(AuthOutcome.Failed(e.message ?: "Google sign-in failed"))
            }
    }

    /** Extrage token-ul din rezultatul ecranului de consimtamant (dupa [AuthOutcome.NeedsResolution]). */
    fun tokenFromResult(data: Intent?): AuthOutcome =
        try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            result.accessToken
                ?.let { AuthOutcome.Token(it) }
                ?: AuthOutcome.Failed("No access token after granting access")
        } catch (e: ApiException) {
            AuthOutcome.Failed(e.message ?: "Google sign-in was cancelled")
        }
}
