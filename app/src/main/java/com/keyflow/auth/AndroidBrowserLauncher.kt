package com.keyflow.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Abre a URL no navegador do sistema (`ACTION_VIEW`) — é o que o login por navegador precisa (RSN-T16). Só aceita `https`: a URL
 * de autorização e a do uso do plano são sempre `https`, e nada de outro esquema deve sair do app por aqui. Sem navegador
 * instalado, falha com [AuthException] para a tela explicar em vez de travar em "aguardando".
 */
class AndroidBrowserLauncher(private val context: Context) : BrowserLauncher {
    override fun open(url: String) {
        if (!url.startsWith("https://")) throw AuthException(AuthErrorKind.UNKNOWN, "só abre https")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw AuthException(AuthErrorKind.UNKNOWN, "nenhum navegador instalado", e)
        }
    }
}
