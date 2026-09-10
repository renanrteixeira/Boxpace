package com.boxpace.presentation.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.boxpace.data.cloud.TokenOAuthProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

/**
 * Launcher do picker OAuth do Drive (Epic 5) — abertura exige [Activity]
 * (AD-SYNC seam aprovado). O escopo é exclusivo `drive.appdata`; o **token é
 * entregue apenas em memória** ao [TokenOAuthProvider] (AD-SYNC-3). A UI nunca
 * fala com a API do Drive — só o coordenador, em `data/cloud`, o faz.
 *
 * Retorna uma função `solicitar()` que abre o picker e, ao sucesso, entrega o
 * token e chama [onVinculado]. Falha de autorização chama [onFalha].
 */
@Composable
fun rememberVincularDriveLauncher(
    tokenOAuthProvider: TokenOAuthProvider,
    onVinculado: () -> Unit,
    onFalha: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    // No Compose o [LocalContext] pode ser um `ContextThemeWrapper`, não a
    // Activity — desembrulha para a Activity real (a authorize exige Activity).
    val activity = remember(context) { context.encontrarAtividade() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { resultado ->
        val data = resultado.data
        if (data != null) {
            try {
                val authResult = Identity.getAuthorizationClient(context)
                    .getAuthorizationResultFromIntent(data)
                val token = authResult.accessToken
                if (!token.isNullOrBlank()) {
                    tokenOAuthProvider.fornecer(token)
                    onVinculado()
                } else {
                    Log.w(TAG, "OAuth sem token no resultado")
                    onFalha()
                }
            } catch (e: ApiException) {
                Log.w(TAG, "OAuth rejeitado pelo Google ${e.status}", e)
                onFalha()
            }
        } else {
            onFalha()
        }
    }

    return remember(tokenOAuthProvider, activity) {
        {
            val act = activity
            if (act == null) {
                Log.e(TAG, "Activity não encontrada a partir do contexto")
                onFalha()
                return@remember
            }
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_APPDATA)))
                .build()
            Identity.getAuthorizationClient(act)
                .authorize(request)
                .addOnSuccessListener { r ->
                    if (r.hasResolution()) {
                        r.pendingIntent?.let { pi ->
                            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        } ?: run {
                            Log.w(TAG, "OAuth pediu resolução sem IntentSender")
                            onFalha()
                        }
                    } else {
                        val token = r.accessToken
                        if (!token.isNullOrBlank()) {
                            tokenOAuthProvider.fornecer(token)
                            onVinculado()
                        } else {
                            Log.w(TAG, "OAuth resolveu sem token")
                            onFalha()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "authorize falhou", e)
                    onFalha()
                }
        }
    }
}

/** Escopo exclusivo do App Data Folder do Drive (`drive.appdata`). */
private const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"

private const val TAG = "VincularDrive"

/** Desembrulha [ContextWrapper] iterativo para a [Activity] raiz (ou null). */
private fun Context.encontrarAtividade(): Activity? {
    var atual: Context? = this
    while (atual is ContextWrapper) {
        if (atual is Activity) return atual
        atual = atual.baseContext
    }
    return null
}
