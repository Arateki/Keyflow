package com.keyflow.overlay

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.keyflow.R
import com.keyflow.core.ButtonState

/**
 * Notificação Ateliê da gravação (NOTIF-01..03): `RemoteViews` escura com orbe/ícone,
 * "● Gravando · m:ss" (Chronometer), waveform estática e ações **Pausar/Retomar** + **Parar e copiar**
 * via `PendingIntent` → [OverlayService].
 *
 * O tempo exclui a pausa: o `base` do Chronometer é deslocado ao retomar (REC-06). [frozenSeconds]
 * força um tempo fixo (sem ticking) p/ screenshot determinístico na galeria.
 */
class RecordingNotification(
    private val context: Context,
    private val channelId: String,
) {
    private var prevState: ButtonState = ButtonState.IDLE_NO_FIELD
    private var base = SystemClock.elapsedRealtime() // elapsed = now - base (enquanto RECORDING)
    private var pauseStart = 0L

    fun build(state: ButtonState, dark: Boolean = true, frozenSeconds: Int? = null): Notification =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setColor(ACCENT_TEAL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Mostra os controles na tela de bloqueio (sujeito às configs de notificação do aparelho).
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(contentView(state, frozenSeconds, dark))
            .setCustomBigContentView(contentView(state, frozenSeconds, dark))
            .build()

    /** RemoteViews isolada p/ inflar na galeria de screenshots (determinística via [frozenSeconds]). */
    fun galleryRemoteViews(state: ButtonState, frozenSeconds: Int): RemoteViews =
        contentView(state, frozenSeconds, dark = true)

    private fun contentView(state: ButtonState, frozenSeconds: Int?, dark: Boolean): RemoteViews {
        manageBase(state)
        val recording = state == ButtonState.RECORDING || state == ButtonState.PAUSED
        val cancelling = state == ButtonState.CANCELLING
        val sending = state == ButtonState.SENDING
        val idle = state == ButtonState.IDLE_READY || state == ButtonState.IDLE_NO_FIELD
        val rv = RemoteViews(context.packageName, R.layout.notif_recording)

        // Tema claro/escuro aplicado por código (acompanha a troca de tema em tempo real). Os chips de ação
        // e o ícone seguem os acentos (teal/record/âmbar), iguais nos dois temas.
        rv.setInt(R.id.notif_root, "setBackgroundColor", if (dark) BG_DARK else BG_LIGHT)
        rv.setTextColor(R.id.notif_title, if (dark) FG_DARK else FG_LIGHT)
        rv.setTextColor(R.id.notif_dot, DOT)
        rv.setTextColor(R.id.notif_time, if (dark) CHRONO_DARK else CHRONO_LIGHT)

        rv.setTextViewText(R.id.notif_title, titleFor(state))

        // Tempo/onda aparecem gravando e durante o cancelamento (tempo congelado).
        val timeVisible = if (recording || cancelling) View.VISIBLE else View.GONE
        listOf(R.id.notif_dot, R.id.notif_time, R.id.notif_wave)
            .forEach { rv.setViewVisibility(it, timeVisible) }
        // Ocioso: só o botão gravar (slot do pause). Gravando: pause + parar + cancelar.
        // Cancelando: descartar-agora (🗑, slot do parar) + desfazer (↶, slot do cancelar).
        // Transcrevendo: só cancelar — não há o que pausar/retomar nem "parar" (já parou pra enviar).
        rv.setViewVisibility(R.id.notif_pause, if (recording || idle) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.notif_stop, if (recording || cancelling) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.notif_cancel, if (recording || cancelling || sending) View.VISIBLE else View.GONE)

        val now = SystemClock.elapsedRealtime()
        val chronoBase = when {
            frozenSeconds != null -> now - frozenSeconds * 1000L
            state == ButtonState.RECORDING -> base
            else -> now - (pauseStart - base) // congela no tempo da pausa/cancelamento
        }
        rv.setChronometer(R.id.notif_time, chronoBase, null, state == ButtonState.RECORDING && frozenSeconds == null)

        // Slot do pause: gravar (●) quando ocioso; play/pause quando gravando/pausado.
        if (idle) {
            rv.setImageViewResource(R.id.notif_pause, R.drawable.ic_notif_record)
            rv.setOnClickPendingIntent(R.id.notif_pause, pending(OverlayService.ACTION_RECORD, REQ_PAUSE))
        } else {
            rv.setImageViewResource(
                R.id.notif_pause,
                if (state == ButtonState.PAUSED) R.drawable.ic_notif_play else R.drawable.ic_notif_pause,
            )
            rv.setOnClickPendingIntent(R.id.notif_pause, pending(ACTION_PAUSE_RESUME, REQ_PAUSE))
        }
        // Slot "parar": enviar enquanto grava; descartar-agora (🗑) durante o cancelamento.
        if (cancelling) {
            rv.setImageViewResource(R.id.notif_stop, R.drawable.ic_notif_trash)
            rv.setOnClickPendingIntent(R.id.notif_stop, pending(OverlayService.ACTION_COMMIT_CANCEL, REQ_STOP))
        } else {
            rv.setImageViewResource(R.id.notif_stop, R.drawable.ic_notif_stop)
            rv.setOnClickPendingIntent(R.id.notif_stop, pending(ACTION_STOP_PASTE, REQ_STOP))
        }
        // Slot "cancelar": cancelar enquanto grava; desfazer (↶) durante o cancelamento.
        if (cancelling) {
            rv.setImageViewResource(R.id.notif_cancel, R.drawable.ic_notif_undo)
            rv.setOnClickPendingIntent(R.id.notif_cancel, pending(OverlayService.ACTION_UNDO_CANCEL, REQ_CANCEL))
        } else {
            rv.setImageViewResource(R.id.notif_cancel, R.drawable.ic_notif_cancel)
            rv.setOnClickPendingIntent(R.id.notif_cancel, pending(ACTION_CANCEL, REQ_CANCEL))
        }
        // ⚙ abre o app do KeyFlow — sempre disponível. Launch de Activity DIRETO (getActivity): iniciar
        // Activity a partir do serviço (getService→startActivity) é bloqueado em background no Android 10+.
        rv.setOnClickPendingIntent(R.id.notif_config, configPending())
        return rv
    }

    /** PendingIntent que abre a Activity do KeyFlow direto da notificação (launch autorizado pelo sistema). */
    private fun configPending(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_OPEN_CONFIG)
        return if (launch.component != null && launch.action == Intent.ACTION_MAIN) {
            PendingIntent.getActivity(
                context,
                REQ_CONFIG,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            // Fallback improvável (sem launcher): mantém a rota antiga pelo serviço.
            PendingIntent.getService(
                context,
                REQ_CONFIG,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    private fun manageBase(state: ButtonState) {
        val now = SystemClock.elapsedRealtime()
        when {
            state == ButtonState.RECORDING && prevState != ButtonState.RECORDING && prevState != ButtonState.PAUSED ->
                base = now // início
            state == ButtonState.RECORDING && prevState == ButtonState.PAUSED ->
                base += now - pauseStart // retomada: desconta a pausa
            // PAUSED e CANCELLING congelam: marca o início da pausa p/ descontar na retomada (REC-06).
            (state == ButtonState.PAUSED || state == ButtonState.CANCELLING) && prevState == ButtonState.RECORDING ->
                pauseStart = now
        }
        prevState = state
    }

    private fun pending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, OverlayService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_PAUSE_RESUME = "com.keyflow.overlay.action.PAUSE_RESUME"
        const val ACTION_STOP_PASTE = "com.keyflow.overlay.action.STOP_PASTE"
        const val ACTION_CANCEL = "com.keyflow.overlay.action.CANCEL"

        private const val REQ_PAUSE = 10
        private const val REQ_STOP = 11
        private const val REQ_CANCEL = 12
        private const val REQ_CONFIG = 13
        private val ACCENT_TEAL = 0xFF4E9683.toInt()

        // Cores por tema (espelham a paleta Ateliê: bg/fg/muted claro|escuro).
        private val BG_DARK = 0xFF2B2720.toInt()
        private val BG_LIGHT = 0xFFF1E7C9.toInt()
        private val FG_DARK = 0xFFF1E7C9.toInt()
        private val FG_LIGHT = 0xFF2B2720.toInt()
        private val DOT = 0xFF9C9072.toInt() // faint (igual nos dois temas)
        private val CHRONO_DARK = 0xFFC3B996.toInt()
        private val CHRONO_LIGHT = 0xFF6A6049.toInt()

        /** Rótulo da ação de pausa conforme o estado (lógica pura, testável). */
        fun pauseLabel(state: ButtonState): String = when (state) {
            ButtonState.PAUSED -> "Retomar"
            else -> "Pausar"
        }

        /** Título da notificação conforme o estado. */
        fun titleFor(state: ButtonState): String = when (state) {
            ButtonState.RECORDING -> "● Gravando"
            ButtonState.PAUSED -> "⏸ Pausado"
            ButtonState.CANCELLING -> "🗑 Cancelado · desfazer"
            ButtonState.SENDING -> "⏳ Transcrevendo"
            else -> "KeyFlow ativo"
        }
    }
}
