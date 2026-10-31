package com.keyflow.computador.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.keyflow.i18n.tr

/**
 * A notificação das sessões SSH (CMP-32): **só a contagem**. Nunca servidor, usuário, comando nem saída. Ações: "Desconectar todas" e, se
 * houver sessão do agente, "Parar o agente". Canal próprio, importância baixa; não usa o canal da orbe.
 */
object NotificacaoSessoes {
    const val CANAL = "sessoes_computador"
    const val ID = 4107

    fun garantirCanal(contexto: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = contexto.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CANAL, tr("Sessões do Computador"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Sessões SSH mantidas conectadas em segundo plano")
                setShowBadge(false)
            },
        )
    }

    /** O título: contagem no singular ou no plural. Público porque o teste confere que nada além disso aparece. */
    fun titulo(n: Int): String = if (n == 1) tr("%d sessão SSH aberta").format(n) else tr("%d sessões SSH abertas").format(n)

    fun montar(contexto: Context, sessoes: Int, comAgente: Boolean, abrir: Intent?): Notification {
        garantirCanal(contexto)
        val b = NotificationCompat.Builder(contexto, CANAL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(titulo(sessoes))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0, tr("Desconectar todas"), acao(contexto, SessoesService.ACAO_DESCONECTAR_TODAS, 1))
        if (comAgente) b.addAction(0, tr("Parar o agente"), acao(contexto, SessoesService.ACAO_PARAR_AGENTE, 2))
        if (abrir != null) b.setContentIntent(PendingIntent.getActivity(contexto, 3, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return b.build()
    }

    private fun acao(contexto: Context, acao: String, codigo: Int): PendingIntent =
        PendingIntent.getService(contexto, codigo, Intent(contexto, SessoesService::class.java).setAction(acao), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
