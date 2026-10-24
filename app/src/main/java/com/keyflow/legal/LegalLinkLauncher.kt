package com.keyflow.legal

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/** Abre o navegador também fora de uma Activity; ausência de app vira feedback na tela chamadora. */
fun openLegalUrl(url: String, launch: (Intent) -> Unit): Boolean = try {
    launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}
