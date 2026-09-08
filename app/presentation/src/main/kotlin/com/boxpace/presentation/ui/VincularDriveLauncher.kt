package com.boxpace.presentation.ui

import android.app.Activity
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
    val activity = context as? Activity

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { resultado ->
        val data = resultado.data
        if (data != null) {
            try {
                val authResult = Identity.getAuthorizationClient(context)
                    .getAuthorizationResultFromIntent(data)
                val token = authResult.accessToken
                if (token != null) {
                    tokenOAuthProvider.fornecer(token)
                    onVinculado()
                } else {
                    onFalha()
                }
            } catch (_: ApiException) {
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
                        } ?: onFalha()
                    } else {
                        val token = r.accessToken
                        if (token != null) {
                            tokenOAuthProvider.fornecer(token)
                            onVinculado()
                        } else {
                            onFalha()
                        }
                    }
                }
                .addOnFailureListener { onFalha() }
        }
    }
}

/** Escopo exclusivo do App Data Folder do Drive (`drive.appdata`). */
private const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
