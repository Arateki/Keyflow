package com.keyflow.notificacao.app

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
import com.keyflow.i18n.tr
import com.keyflow.notificacao.Botao
import com.keyflow.notificacao.Botoes
import com.keyflow.notificacao.BotoesDaNotificacao
import com.keyflow.notificacao.ConfigEfetiva
import com.keyflow.notificacao.Contraste
import com.keyflow.notificacao.ConfigNotificacao
import com.keyflow.notificacao.CronometroDaNotificacao
import com.keyflow.notificacao.EstadoVisual
import com.keyflow.notificacao.EstadoVisualDaNotificacao
import com.keyflow.overlay.OverlayService

/**
 * A notificação **é a orbe** (NTF-05..07, NTF-13, NTF-17, NTF-20, NTF-21, NTF-29, NTF-45, NTF-48, NTF-50, design §6): a linha
 * `[botão][orbe][botão]` de 48 dp (`notif_orb`), um clique na orbe (os toques 1/2/3 são contados pelo serviço), **um `PendingIntent` por botão**
 * (ações dedicadas), o corpo abre o KeyFlow (`getActivity`) e dispensar avisa o serviço (`deleteIntent`). Só `setCustomContentView`: sem
 * `BigContentView` (não duplica bitmap). **Nunca recebe texto**: a descrição de acessibilidade sai do estado e das chaves `tr` (NTF-17).
 *
 * O tempo exclui a pausa: a base do `Chronometer` é deslocada ao retomar (REC-06); [segundosCongelados] fixa um tempo (galeria/screenshots).
 */
class NotificacaoDaOrbe(
    private val context: Context,
    private val canalDaOrbe: String,
    private val canalMinimo: String = CANAL_MINIMO,
) {
    private var anterior: ButtonState = ButtonState.IDLE_NO_FIELD
    private var base = SystemClock.elapsedRealtime() // tempo decorrido = agora − base (enquanto RECORDING)
    private var inicioDaPausa = 0L

    /** A `Notification` completa do estado. [publica] é a aparência da versão pública (orbe em repouso) usada com a tela bloqueada. */
    fun construir(
        estado: ButtonState,
        visaoAtiva: Boolean,
        aparencia: AparenciaDaNotificacao,
        aparenciaEmRepouso: AparenciaDaNotificacao,
        config: ConfigNotificacao,
        efetiva: ConfigEfetiva,
        escuro: Boolean,
        segundosCongelados: Int? = null,
    ): Notification {
        val visual = EstadoVisualDaNotificacao.para(estado, visaoAtiva, efetiva)
        val botoes = BotoesDaNotificacao.para(estado, visaoAtiva)
        val base = gerirBase(estado)
        val conteudo = linha(visual, botoes, aparencia, escuro, base, segundosCongelados, estado)
        // Versão pública (tela bloqueada, NTF-16/17): com a opção desligada, a orbe em repouso, sem cronômetro e sem botões; ligada, o real.
        val publica = if (config.permitirComTelaBloqueada) {
            linha(visual, botoes, aparencia, escuro, base, segundosCongelados, estado)
        } else {
            val repouso = EstadoVisualDaNotificacao.para(ButtonState.IDLE_NO_FIELD, false, efetiva)
            linha(repouso, Botoes.NENHUM, aparenciaEmRepouso, escuro, base, null, ButtonState.IDLE_NO_FIELD)
        }
        return NotificationCompat.Builder(context, canalDaOrbe)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, canalDaOrbe)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomContentView(publica)
                    .setContentIntent(abrirOKeyFlow())
                    .build(),
            )
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(conteudo)
            .setContentIntent(abrirOKeyFlow())
            .setDeleteIntent(servico(OverlayService.ACTION_NOTIF_DISMISSED, REQ_DISPENSAR))
            .build()
    }

    /** A notificação mínima (NTF-21): sem orbe, sem ação, `SECRET`, importância mínima; usada com `mostrar = false` e quando a aparência falha. */
    fun construirMinima(): Notification =
        NotificationCompat.Builder(context, canalMinimo)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(tr("KeyFlow ativo"))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(abrirOKeyFlow())
            .setDeleteIntent(servico(OverlayService.ACTION_NOTIF_DISMISSED, REQ_DISPENSAR))
            .build()

    /** A `RemoteViews` isolada, para a galeria de screenshots (tempo fixo, sem `Chronometer` correndo). */
    fun galeria(
        estado: ButtonState,
        visaoAtiva: Boolean,
        aparencia: AparenciaDaNotificacao,
        efetiva: ConfigEfetiva,
        escuro: Boolean,
        segundos: Int,
    ): RemoteViews = linha(
        EstadoVisualDaNotificacao.para(estado, visaoAtiva, efetiva), BotoesDaNotificacao.para(estado, visaoAtiva),
        aparencia, escuro, SystemClock.elapsedRealtime(), segundos, estado,
    )

    private fun linha(
        visual: EstadoVisual,
        botoes: Botoes,
        aparencia: AparenciaDaNotificacao,
        escuro: Boolean,
        baseDoTempo: Long,
        segundosCongelados: Int?,
        estado: ButtonState,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.notif_orb)
        // O fundo só é pintado quando a skin declara um (Ateliê; pacote com notification.background); senão fica o do sistema.
        aparencia.fundo?.let { rv.setInt(R.id.notif_root, "setBackgroundColor", it) }
        rv.setImageViewBitmap(R.id.notif_orb, aparencia.orbe)
        rv.setContentDescription(R.id.notif_orb, visual.descricaoTraduzida { tr(it) })
        rv.setOnClickPendingIntent(R.id.notif_orb, servico(OverlayService.ACTION_NOTIF_ORB_TAP, REQ_ORBE))

        // Os glifos dos botões são vetoriais, tingidos: a tinta da skin quando ela pinta fundo, senão a cor primária do sistema no modo atual.
        val tintaDosBotoes = if (aparencia.fundo != null) Contraste.legivel(aparencia.tinta, aparencia.fundo) else if (escuro) COR_CLARA else COR_ESCURA
        botao(rv, R.id.notif_btn_esq, botoes.esquerda, tintaDosBotoes)
        botao(rv, R.id.notif_btn_dir, botoes.direita, tintaDosBotoes)

        val agora = SystemClock.elapsedRealtime()
        val tempoBase = when {
            segundosCongelados != null -> agora - segundosCongelados * 1000L
            estado == ButtonState.RECORDING -> baseDoTempo
            else -> agora - (inicioDaPausa - baseDoTempo) // congela no tempo da pausa/cancelamento
        }
        val correndo = visual.cronometro == CronometroDaNotificacao.CORRENDO_NO_CENTRO && segundosCongelados == null
        for ((id, modo) in listOf(
            R.id.notif_time to CronometroDaNotificacao.CORRENDO_NO_CENTRO,
            R.id.notif_time_mini to CronometroDaNotificacao.PARADO_MIUDO,
        )) {
            val aparece = visual.cronometro == modo
            rv.setViewVisibility(id, if (aparece) View.VISIBLE else View.GONE)
            if (aparece) {
                rv.setChronometer(id, tempoBase, null, correndo && modo == CronometroDaNotificacao.CORRENDO_NO_CENTRO)
                // O tempo no centro fica sobre a orbe (tinta da skin); o miúdo, abaixo do glifo, cai no fundo da linha: precisa contrastar com ele.
                rv.setTextColor(id, if (modo == CronometroDaNotificacao.PARADO_MIUDO) tintaDosBotoes else aparencia.tinta)
            }
        }
        return rv
    }

    private fun botao(rv: RemoteViews, id: Int, botao: Botao?, tinta: Int) {
        if (botao == null) {
            rv.setViewVisibility(id, View.INVISIBLE) // INVISIBLE, não GONE: a orbe não anda entre os estados (NTF-T1)
            return
        }
        rv.setViewVisibility(id, View.VISIBLE)
        rv.setImageViewResource(id, glifoDoBotao(botao))
        rv.setInt(id, "setColorFilter", tinta)
        rv.setContentDescription(id, tr(BotoesDaNotificacao.descricao(botao)))
        // Um PendingIntent por botão, com o botão no extra: um clique atrasado (o estado já mudou) é reconhecido e ignorado pelo serviço.
        val (acao, codigo) = when (botao) {
            Botao.PAUSAR -> OverlayService.ACTION_NOTIF_PAUSE_RESUME to REQ_PAUSAR
            Botao.RETOMAR -> OverlayService.ACTION_NOTIF_PAUSE_RESUME to REQ_RETOMAR
            Botao.DESCARTAR -> OverlayService.ACTION_NOTIF_CANCEL to REQ_DESCARTAR
            Botao.ENCERRAR -> OverlayService.ACTION_NOTIF_CANCEL to REQ_ENCERRAR
            Botao.DESFAZER -> OverlayService.ACTION_NOTIF_UNDO to REQ_DESFAZER
        }
        rv.setOnClickPendingIntent(id, servico(acao, codigo, botao))
    }

    private fun glifoDoBotao(botao: Botao): Int = when (botao) {
        Botao.PAUSAR -> R.drawable.ic_notif_pause
        Botao.RETOMAR -> R.drawable.ic_notif_play
        Botao.DESCARTAR -> R.drawable.ic_notif_trash
        Botao.DESFAZER -> R.drawable.ic_notif_undo
        Botao.ENCERRAR -> R.drawable.ic_notif_cancel
    }

    /** O corpo abre o KeyFlow (o equivalente do arrasto ↓, NTF-13). `getActivity` direto: o sistema autoriza o início da Activity. */
    private fun abrirOKeyFlow(): PendingIntent {
        val abrir = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (abrir != null) {
            PendingIntent.getActivity(context, REQ_CORPO, abrir, FLAGS)
        } else {
            servico(OverlayService.ACTION_OPEN_CONFIG, REQ_CORPO)
        }
    }

    private fun servico(acao: String, codigo: Int, botao: Botao? = null): PendingIntent =
        PendingIntent.getService(
            context, codigo,
            Intent(context, OverlayService::class.java).setAction(acao).apply { botao?.let { putExtra(EXTRA_BOTAO, it.name) } },
            FLAGS,
        )

    private fun gerirBase(estado: ButtonState): Long {
        val agora = SystemClock.elapsedRealtime()
        when {
            estado == ButtonState.RECORDING && anterior != ButtonState.RECORDING && anterior != ButtonState.PAUSED -> base = agora
            estado == ButtonState.RECORDING && anterior == ButtonState.PAUSED -> base += agora - inicioDaPausa
            (estado == ButtonState.PAUSED || estado == ButtonState.CANCELLING) && anterior == ButtonState.RECORDING -> inicioDaPausa = agora
        }
        anterior = estado
        return base
    }

    companion object {
        const val CANAL_MINIMO = "keyflow_overlay_min"

        /** O extra do clique de um botão: o nome do [Botao]. */
        const val EXTRA_BOTAO = "com.keyflow.overlay.extra.NOTIF_BOTAO"
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private const val REQ_ORBE = 20
        private const val REQ_PAUSAR = 21
        private const val REQ_DESCARTAR = 22
        private const val REQ_DESFAZER = 23
        private const val REQ_RETOMAR = 26
        private const val REQ_ENCERRAR = 27
        private const val REQ_CORPO = 24
        private const val REQ_DISPENSAR = 25
        private const val COR_CLARA = 0xFFFFFFFF.toInt()
        private const val COR_ESCURA = 0xFF1B1B1B.toInt()
    }
}
