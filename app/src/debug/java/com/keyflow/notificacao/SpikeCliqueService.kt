package com.keyflow.notificacao

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/** Prova de altura NTF-T1 (só debug, descartável): registra qual clique da notificação chegou ao serviço e quando. */
class SpikeCliqueService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        eventos += Evento(intent?.action.orEmpty(), SystemClock.elapsedRealtimeNanos())
        stopSelf(startId)
        return START_NOT_STICKY
    }

    data class Evento(val acao: String, val instanteNs: Long)

    companion object {
        val eventos = CopyOnWriteArrayList<Evento>()
    }
}
