package com.keyflow.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import com.keyflow.skins.PackOrb
import com.keyflow.skins.SkinIds
import com.keyflow.skins.SkinPackStore
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.orbe.*
import com.keyflow.overlay.Haptics
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.orbCenterInk
import com.keyflow.ui.orb.toPackState

/** Passo da pele que anda (~15 quadros por segundo) e do pacote que ainda não entregou o quadro. */
private const val QUADRO_DA_PELE_MS = 66L

/** Desenho e gesto locais; o pai decide o alvo antes de despachar aos filhos. Sem janela de overlay. */
internal class OrbeDoTecladoView(
    private val host: View,
    private val dark: Boolean,
    private val frozenSeconds: Int?,
    private val clock: () -> Long,
    private val aoComando: (ComandoDaOrbeDoTeclado) -> Unit,
    private val aoPressionar: () -> Unit,
    private val aoMudar: () -> Unit,
    // Pele da orbe (KBO-22, KBO-23): o direito às skins pagas e se a pele anda. `motionTimeMs` congela (prévia e golden); no teclado real
    // (`motionEnabled`) ela corre, a não ser com "reduzir animações" ou economia de bateria (`MotionPolicy`).
    private val skinsUnlocked: Boolean = false,
    private val motionEnabled: Boolean = false,
    private val motionTimeMs: Float? = null,
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
    private val pele = OrbeDoTecladoPele(host.resources.displayMetrics.density)
    private var pacote: PackOrb? = null
    private var idDoPacote: String? = null
    private var inicioDoMovimentoMs = SystemClock.uptimeMillis()
    private var ultimoCirculo: Circulo? = null
    private var pacotePreparado: Pair<OrbState, Int>? = null

    /** Cor lisa do teclado atrás da orbe (0 = teclado de vidro, sem fundo): o halo e o vidro da pele a atravessam. */
    var fundo = 0
        set(valor) {
            field = valor
            host.invalidate()
        }

    private val animada: Boolean get() = motionTimeMs == null && motionEnabled && MotionPolicy.allowed(host.context)
    private fun tempoMs(): Float = motionTimeMs
        ?: if (animada) (SystemClock.uptimeMillis() - inicioDoMovimentoMs).toFloat() else TEMPO_CONGELADO_DA_ORBE_MS

    private fun precisaDeTique() = host.isShown &&
        (animada || pele.aguardandoPacote || (estado == ButtonState.RECORDING && frozenSeconds == null))

    private val ticker = object : Runnable {
        override fun run() {
            ultimoCirculo?.let { host.invalidate(it.esquerda, it.topo, it.direita, it.fundo) } ?: host.invalidate()
            if (precisaDeTique()) handler.postDelayed(this, if (animada || pele.aguardandoPacote) QUADRO_DA_PELE_MS else 1000)
        }
    }

    private fun agendarTique() {
        handler.removeCallbacks(ticker)
        if (precisaDeTique()) handler.post(ticker)
    }

    fun atualizarConfig(config: ConfigOrbeTeclado, settings: Settings) {
        this.config = config
        this.settings = settings
        sincronizarPacote()
        agendarTique()
        host.invalidate()
    }

    private fun skinEfetiva(): String = ResolvedorDaOrbeDoTeclado.efetiva(config, settings).skin

    /** O pacote da skin escolhida, aberto fora da main; até ele chegar a orbe veste o Ateliê (KBO-23). */
    private fun sincronizarPacote() {
        val id = skinEfetiva().takeIf { SkinIds.isPack(it) }
        if (id == idDoPacote) return
        idDoPacote = id
        pacote?.close()
        pacote = null
        pacotePreparado = null
        if (id == null) return
        val contexto = host.context.applicationContext
        Thread {
            val aberto = try { PackOrb.open(SkinPackStore.of(contexto), id) } catch (_: Exception) { null }
            handler.post {
                if (idDoPacote == id && pacote == null) {
                    pacote = aberto
                    agendarTique()
                    host.invalidate()
                } else aberto?.close()
            }
        }.start()
    }

    fun atualizarEstado(estado: ButtonState, visaoAtiva: Boolean = this.visaoAtiva) {
        this.estado = estado
        this.visaoAtiva = visaoAtiva
        agendarTique()
        acessibilidade.contentDescription = descricao
        aoMudar()
    }

    fun anexar() {
        inicioDoMovimentoMs = SystemClock.uptimeMillis()
        sincronizarPacote()
        atualizarEstado(estado)
    }
    fun desanexar() {
        handler.removeCallbacksAndMessages(null)
        pacote?.close()
        pacote = null
        idDoPacote = null
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
        ultimoCirculo = circulo
        val state = when (estado) {
            ButtonState.RECORDING -> OrbState.Listening
            ButtonState.PAUSED, ButtonState.CANCELLING -> OrbState.Paused
            ButtonState.SENDING -> OrbState.Transcribing
            else -> OrbState.Idle
        }
        val estilo = EscolhaDaPeleDaOrbe.estilo(skinEfetiva(), skinsUnlocked, pacote != null)
        val pacoteAtivo = pacote.takeIf { estilo == OrbStyle.PACK }
        pacoteAtivo?.let { if (pacotePreparado != state to circulo.diametro) { it.prepare(state.toPackState(), circulo.diametro); pacotePreparado = state to circulo.diametro } }
        val quadro = pele.quadro(
            estilo, pacoteAtivo, state, circulo.diametro, fundo, GeometriaDaOrbe.ehSobressalente(estado, visaoAtiva), tempoMs(),
            deterministico = !animada,
        )
        if (pele.aguardandoPacote) agendarTique()
        canvas.save()
        val recorte = Path().apply { addCircle(circulo.centroX.toFloat(), circulo.centroY.toFloat(), circulo.raio.toFloat(), Path.Direction.CW) }
        canvas.clipPath(recorte)
        canvas.drawBitmap(quadro, circulo.esquerda.toFloat(), circulo.topo.toFloat(), null)
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
        paint.color = orbCenterInk(estilo, pacoteAtivo).toArgb()
        // O tempo com glifo ("⏸ 0:12") é mais largo que o círculo: letra menor para caber dentro dele.
        paint.textSize = circulo.diametro * if (centro.length > 4) 0.12f else 0.18f
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
