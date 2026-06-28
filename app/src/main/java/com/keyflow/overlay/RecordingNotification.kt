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

    fun build(state: ButtonState, frozenSeconds: Int? = null): Notification =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setColor(ACCENT_TEAL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(contentView(state, frozenSeconds))
            .setCustomBigContentView(contentView(state, frozenSeconds))
            .build()

    /** RemoteViews isolada p/ inflar na galeria de screenshots (determinística via [frozenSeconds]). */
    fun galleryRemoteViews(state: ButtonState, frozenSeconds: Int): RemoteViews =
        contentView(state, frozenSeconds)

    private fun contentView(state: ButtonState, frozenSeconds: Int?): RemoteViews {
        manageBase(state)
        val recording = state == ButtonState.RECORDING || state == ButtonState.PAUSED
        val rv = RemoteViews(context.packageName, R.layout.notif_recording)

        rv.setTextViewText(R.id.notif_title, titleFor(state))

        val visible = if (recording) View.VISIBLE else View.GONE
        listOf(R.id.notif_dot, R.id.notif_time, R.id.notif_wave, R.id.notif_pause, R.id.notif_stop)
            .forEach { rv.setViewVisibility(it, visible) }

        val now = SystemClock.elapsedRealtime()
        val chronoBase = when {
            frozenSeconds != null -> now - frozenSeconds * 1000L
            state == ButtonState.RECORDING -> base
            else -> now - (pauseStart - base) // congela no tempo da pausa
        }
        rv.setChronometer(R.id.notif_time, chronoBase, null, state == ButtonState.RECORDING && frozenSeconds == null)

        rv.setTextViewText(R.id.notif_pause, pauseLabel(state))
        rv.setOnClickPendingIntent(R.id.notif_pause, pending(ACTION_PAUSE_RESUME, REQ_PAUSE))
        rv.setOnClickPendingIntent(R.id.notif_stop, pending(ACTION_STOP_PASTE, REQ_STOP))
        return rv
    }

    private fun manageBase(state: ButtonState) {
        val now = SystemClock.elapsedRealtime()
        when {
            state == ButtonState.RECORDING && prevState != ButtonState.RECORDING && prevState != ButtonState.PAUSED ->
                base = now // início
            state == ButtonState.RECORDING && prevState == ButtonState.PAUSED ->
                base += now - pauseStart // retomada: desconta a pausa
            state == ButtonState.PAUSED && prevState == ButtonState.RECORDING ->
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

        private const val REQ_PAUSE = 10
        private const val REQ_STOP = 11
        private val ACCENT_TEAL = 0xFF4E9683.toInt()

        /** Rótulo da ação de pausa conforme o estado (lógica pura, testável). */
        fun pauseLabel(state: ButtonState): String = when (state) {
            ButtonState.PAUSED -> "Retomar"
            else -> "Pausar"
        }

        /** Título da notificação conforme o estado. */
        fun titleFor(state: ButtonState): String = when (state) {
            ButtonState.RECORDING -> "● Gravando"
            ButtonState.PAUSED -> "⏸ Pausado"
            else -> "KeyFlow ativo"
        }
    }
}
