package com.keyflow.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.orbe.*
import com.keyflow.overlay.Haptics
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.drawOrb

/** Desenho e gesto locais; o pai decide o alvo antes de despachar aos filhos. Sem janela de overlay. */
internal class OrbeDoTecladoView(
    private val host: View,
    private val dark: Boolean,
    private val frozenSeconds: Int?,
    private val clock: () -> Long,
    private val aoComando: (ComandoDaOrbeDoTeclado) -> Unit,
    private val aoPressionar: () -> Unit,
    private val aoMudar: () -> Unit,
) {
    var estado = ButtonState.IDLE_NO_FIELD
        private set
    var visaoAtiva = false
        private set
    var config = ConfigOrbeTeclado()
        private set
    var settings = Settings()
        private set
    val acessibilidade = object : View(host.context) {
        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }.apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(0, 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        setOnClickListener { clicarAcessivel() }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val classificador = ClassificadorDeGesto(clock) { settings.tapBurstWindowMs.toLong() }
    private val scope = CanvasDrawScope()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var segurou = false
    val arrastoAtivo: Boolean get() = segurou
    private var deslocou = false
    private var inicioX = 0f
    private var inicioY = 0f
    private val segurar = Runnable {
        classificador.avaliarSegurar()?.let {
            segurou = true
            resolver(it)
            host.invalidate()
        }
    }
    private val assentar = object : Runnable {
        override fun run() {
            val n = classificador.assentarContagemDeToques()
            if (n != null) {
                val acao = ResolvedorDaOrbeDoTeclado.efetiva(config, settings).acaoParaToques(n)
                executar(ResolvedorDaOrbeDoTeclado.resolverToques(n, config, settings, estado, visaoAtiva = visaoAtiva),
                    recusa = n in 1..3 && acao in ResolvedorDaOrbeDoTeclado.CATALOGO_DE_TOQUES && acao != AcaoDaOrbeDoTeclado.Nenhuma)
            }
            else classificador.rajada.tempoRestanteMs()?.let { handler.postDelayed(this, it.coerceAtLeast(1)) }
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            host.invalidate()
            if (estado == ButtonState.RECORDING && frozenSeconds == null) handler.postDelayed(this, 1000)
        }
    }

    fun atualizarConfig(config: ConfigOrbeTeclado, settings: Settings) {
        this.config = config
        this.settings = settings
        host.invalidate()
    }

    fun atualizarEstado(estado: ButtonState, visaoAtiva: Boolean = this.visaoAtiva) {
        this.estado = estado
        this.visaoAtiva = visaoAtiva
        handler.removeCallbacks(ticker)
        if (estado == ButtonState.RECORDING && frozenSeconds == null && host.isShown) handler.post(ticker)
        acessibilidade.contentDescription = descricao
        aoMudar()
    }

    fun anexar() { atualizarEstado(estado) }
    fun desanexar() {
        handler.removeCallbacksAndMessages(null)
        classificador.aoCancelar()
        classificador.rajada.cancelar()
        segurou = false
    }

    private fun segundos(): Int {
        frozenSeconds?.let { return it }
        val trecho = if (estado == ButtonState.RECORDING) (clock() - KeyFlowController.recordingSegmentStartMs).coerceAtLeast(0) else 0L
        return ((KeyFlowController.recordingAccumulatedMs + trecho) / 1000).toInt()
    }
    private fun tempo(): String = segundos().let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }

    val rotulo: String get() = when {
        visaoAtiva -> "◉"
        estado == ButtonState.RECORDING -> "● ${tempo()}"
        estado == ButtonState.PAUSED -> "⏸ ${tempo()}"
        estado == ButtonState.CANCELLING -> "🗑 ${tempo()}"
        estado == ButtonState.SENDING -> "···"
        estado == ButtonState.ERROR -> "✖"
        estado == ButtonState.PENDING_PASTE -> tr("📋 colar")
        else -> "🎙"
    }
    val descricao: String get() = when {
        visaoAtiva -> tr("Visão ativa: toque para encerrar")
        estado == ButtonState.PENDING_PASTE -> tr("Texto pendente: toque para colar")
        else -> when (estado) {
            ButtonState.IDLE_READY -> tr("Orbe do teclado: pronta")
            ButtonState.IDLE_NO_FIELD -> tr("Orbe do teclado: sem campo")
            ButtonState.RECORDING -> tr("Orbe do teclado: gravando")
            ButtonState.PAUSED -> tr("Orbe do teclado: pausada")
            ButtonState.CANCELLING -> tr("Orbe do teclado: cancelando")
            ButtonState.SENDING -> tr("Orbe do teclado: transcrevendo")
            ButtonState.ERROR -> tr("Orbe do teclado: erro")
            ButtonState.PENDING_PASTE -> error("Estado tratado acima")
        }
    }

    fun clicarAcessivel() {
        classificador.rajada.registrarToque()
        handler.removeCallbacks(assentar)
        handler.postDelayed(assentar, settings.tapBurstWindowMs.toLong())
    }

    fun toque(evento: MotionEvent, circulo: Circulo) {
        when (evento.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                aoPressionar()
                segurou = false
                deslocou = false
                inicioX = evento.x
                inicioY = evento.y
                classificador.aoPressionar(evento.x, evento.y, circulo.diametro.toFloat(), circulo.centroX.toFloat(), host.width / 2f)
                handler.postDelayed(segurar, classificador.limiarSegurarMs())
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(evento.x - inicioX) > 10 || kotlin.math.abs(evento.y - inicioY) > 10) deslocou = true
                if (segurou) classificador.aoMover(evento.x, evento.y, estado == ButtonState.SENDING)
                else if (deslocou) handler.removeCallbacks(segurar)
                host.invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(segurar)
                if (deslocou && !segurou) {
                    classificador.aoCancelar()
                    classificador.rajada.cancelar()
                } else {
                    val interacao = classificador.aoSoltar()
                    if (interacao != null) {
                        resolver(interacao)
                        if (interacao == Interacao.SEGURAR) abrirJanelaPtt()
                    } else if (segurou) abrirJanelaPtt()
                    else {
                        handler.removeCallbacks(assentar)
                        handler.postDelayed(assentar, settings.tapBurstWindowMs.toLong())
                        if (estado == ButtonState.RECORDING) abrirJanelaPtt()
                    }
                }
                segurou = false
                host.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(segurar)
                handler.removeCallbacks(assentar)
                classificador.aoCancelar()
                classificador.rajada.cancelar()
                segurou = false
                host.invalidate()
            }
        }
    }

    private fun abrirJanelaPtt() {
        if (!visaoAtiva && ResolvedorDaOrbeDoTeclado.efetiva(config, settings).modo == ModoDaOrbe.SEGURAR_PARA_FALAR &&
            estado in setOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD, ButtonState.RECORDING, ButtonState.PAUSED)) {
            // A confirmação do microfone chega depois do gesto; o serviço conserva o prazo até lá.
            aoComando(ComandoDaOrbeDoTeclado.AbrirJanelaEnvioPtt)
        }
    }
    private fun resolver(interacao: Interacao) = executar(
        ResolvedorDaOrbeDoTeclado.resolverInteracao(interacao, config, settings, estado, visaoAtiva = visaoAtiva),
        recusa = !visaoAtiva,
    )
    private fun executar(comando: ComandoDaOrbeDoTeclado, recusa: Boolean = false) {
        if (comando != ComandoDaOrbeDoTeclado.Nenhum || recusa) Haptics.tick(host.context, settings.vibrationIntensity)
        if (comando != ComandoDaOrbeDoTeclado.Nenhum) aoComando(comando)
    }

    fun desenhar(canvas: Canvas, circulo: Circulo, topoFaixa: Int, fundoFaixa: Int) {
        if (circulo.vazio) return
        canvas.save()
        val recorte = Path().apply { addCircle(circulo.centroX.toFloat(), circulo.centroY.toFloat(), circulo.raio.toFloat(), Path.Direction.CW) }
        canvas.clipPath(recorte)
        canvas.translate(circulo.esquerda.toFloat(), circulo.topo.toFloat())
        val state = when (estado) {
            ButtonState.RECORDING -> OrbState.Listening
            ButtonState.PAUSED, ButtonState.CANCELLING -> OrbState.Paused
            ButtonState.SENDING -> OrbState.Transcribing
            else -> OrbState.Idle
        }
        scope.draw(Density(host.resources.displayMetrics.density), LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(canvas), Size(circulo.diametro.toFloat(), circulo.diametro.toFloat())) {
            drawOrb(1234f, state, hasCenter = GeometriaDaOrbe.ehSobressalente(estado, visaoAtiva))
        }
        canvas.restore()
        val centro = when {
            visaoAtiva -> "◉"
            estado == ButtonState.RECORDING -> tempo()
            estado == ButtonState.PAUSED -> "⏸ ${tempo()}"
            estado == ButtonState.CANCELLING -> "🗑 ${tempo()}"
            estado == ButtonState.ERROR -> "✖"
            estado == ButtonState.SENDING -> "···"
            else -> ""
        }
        paint.color = if (dark) 0xFFF4ECCF.toInt() else 0xFF2B2720.toInt()
        paint.textSize = circulo.diametro * 0.18f
        canvas.drawText(centro, circulo.centroX.toFloat(), circulo.centroY - (paint.ascent() + paint.descent()) / 2, paint)
        if (segurou && fundoFaixa > topoFaixa) {
            canvas.save()
            canvas.clipRect(0, topoFaixa, host.width, fundoFaixa)
            paint.textSize = (fundoFaixa - topoFaixa) * 0.5f
            val lateral = if (circulo.centroX < host.width / 2) "→" else "←"
            canvas.drawText("↑ $lateral ↓", circulo.centroX.toFloat(), fundoFaixa - paint.descent(), paint)
            canvas.restore()
        }
    }
}
