package com.keyflow.data.models

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * `ACTION_DOWNLOAD_COMPLETE` (NM-T7). O DownloadProvider manda esse broadcast direto para o pacote que
 * pediu o download (`setPackage`), então o receiver do manifest acorda o app mesmo morto; exige a
 * permissão de assinatura que só o DownloadProvider tem, para ninguém mais disparar verificação de
 * centenas de MB. O broadcast é só um aviso: quem decide é a [ModelDownloader.reconcile], que consulta
 * o sistema — um aviso perdido é recuperado quando o gerenciador abre.
 *
 * Verificar e copiar um modelo leva segundos; `goAsync` dá a janela de broadcast em segundo plano
 * (60 s). Se o processo morrer no meio, o `downloads.json` continua lá e a próxima reconciliação refaz.
 */
class ModelDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "model-download-complete") {
            try {
                val results = ModelDownloader.get(app).reconcile()
                Log.i(TAG, "reconciliação após conclusão: $results")
            } catch (e: Exception) {
                Log.e(TAG, "reconciliação falhou", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ModelDownloadReceiver"
    }
}
