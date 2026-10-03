package com.keyflow.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.keyflow.core.ButtonState
import com.keyflow.core.ErrorNavTarget
import com.keyflow.core.KeyFlowController
import com.keyflow.core.OverlaySide
import com.keyflow.core.RecordingMode
import com.keyflow.data.OverlayPosition
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbDragHint
import com.keyflow.ui.orb.OrbDragKind
import com.keyflow.ui.orb.OrbState
import com.keyflow.skins.PackOrb
import com.keyflow.ui.orb.LocalOrbPack
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.orb.toPackState
import kotlin.math.roundToInt

/**
 * Botão flutuante do overlay no estilo Ateliê (OVL-01..06, REC-05). Substitui o antigo
 * `FloatingButtonView` (Canvas): hospeda um [VoiceOrb] num [ComposeView] e desenha o tempo (`m:ss`)
 * ou o glifo de pause no centro conforme o [ButtonState].
 *
 * Gestos: alimenta o [GestureInterpreter] e o [RecordingGestureController], emitindo [GestureIntent]
 * via [onIntent]. Anti-movimento e pausa por modo vêm do controller; o arrasto reposiciona a janela.
 * No PTT, o `UP` agenda o [RecordingGestureController.onReleaseTimeout] após a janela `Wptt`.
 *
 * Os tree-owners do Compose são providos externamente (o `OverlayService` anexa um `ComposeWindowHost`
 * a esta view antes do `WindowManager.addView`).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class OrbButtonView(
    context: Context,
    private val controller: RecordingGestureController,
    private val mode: () -> RecordingMode,
    // Mão (canhoto/destro): define a direção do arrasto lateral de cancelar/retomar (sempre p/ o centro).
    private val side: () -> OverlaySide = { OverlaySide.RIGHT },
    private val onIntent: (GestureIntent) -> Unit,
    private val onPositionChanged: (OverlayPosition) -> Unit = {},
    private val onErrorTap: () -> Unit = {},
    // Atalho de N toques (2/3) resolvido fora (OverlayService): mapeia para ação + gating idle/pause.
    private val onShortcut: (Int) -> Unit = {},
    // Intensidade da vibração ao interagir com a orbe (0 = sem vibração; 1..100) — lida das Settings.
    private val vibrationIntensity: () -> Int = { 0 },
    // Setas do modo-arrasto numa sobreposição separada (null = some). Não mexe na janela da orbe.
    private val onDragArrows: (DragArrowsState?) -> Unit = {},
    // Arrastar ↓ abre o app do KeyFlow mantendo o estado atual da orbe (Toggle).
    private val onOpenConfig: () -> Unit = {},
    orbSizePx: Int = 156,
    // Estilo do orbe (skin da orbe, aba Sobrepor; o Aero é uma bolha de vidro). Troca ao vivo: [setOrbStyle].
    orbStyle: OrbStyle = OrbStyle.ATELIE,
    // O pacote de skin que o estilo PACK desenha (SKP-ORB); `null` = nenhum. Troca ao vivo: [setOrbPack].
    orbPack: PackOrb? = null,
    // Janela máxima entre toques de uma "rajada" de atalho (threshold configurável na aba Sobrepor).
    tapBurstWindowMs: Long = DEFAULT_TAP_BURST_WINDOW_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : FrameLayout(context) {

    // Threshold da rajada de toques ("Tempo de reação"), ajustável ao vivo pelo slider (aba Sobrepor).
    private var tapBurstWindowMs: Long = tapBurstWindowMs

    // Limiar único: toque×segurada e janelas derivam do "Tempo de reação". Hold = janela + 150ms.
    private var gestures = newGestures(this.tapBurstWindowMs)

    /**
     * Relógio MONOTÔNICO, de propósito: o hold é agendado com `handler.postDelayed`, que conta em
     * `uptimeMillis`, e quem decide se virou hold é `onHoldElapsed`, que compara contra o MESMO limiar.
     * Com réguas diferentes (o default do interpretador é o relógio de parede) as duas medidas
     * divergiam alguns milissegundos e o disparo às vezes caía um fio ANTES do limiar — `onHoldElapsed`
     * devolvia null e, como o runnable não se reagenda, o gesto perdia o modo-arrasto de vez.
     * Medido no A32 em 2026-09-08: `elapsed=269 threshold=270 ev=null` contra 270..286 nos que travaram,
     * ~1 gesto em 15. Não era falha de teste: acontecia na mão do usuário do mesmo jeito.
     */
    private fun newGestures(window: Long) = GestureInterpreter(
        tripleTapWindowMs = window,
        holdThresholdMs = window + HOLD_OFFSET_MS,
    )

    fun setTapBurstWindowMs(ms: Long) {
        if (ms <= 0) return
        tapBurstWindowMs = ms
        gestures = newGestures(ms) // mantém o limiar do hold em sincronia (não no meio de um gesto)
    }

    // Toque na orbe durante a janela de desfazer (CANCELLING): conclui o descarte JÁ, sem threshold, e
    // engole o resto do gesto. Assim o descarte é imediato e os toques seguintes já fluem como atalhos.
    private var cancelCommitGesture = false

    // Modo-arrasto do Alternar (segurar trava): ↑ pausa, ← cancela, com feedback de proximidade.
    private var dragLocked = false
    private var dragHoldRunnable: Runnable? = null
    private var dragKind: OrbDragKind? = null
    private var dragProx = 0f
    // Retorno tátil progressivo do arrasto: direção e maior degrau já "tocado" (p/ não repetir o mesmo).
    private var dragHapticKind: OrbDragKind? = null
    private var dragHapticStep = 0
    private var dragCenterX = 0
    private var dragCenterY = 0
    private var dragActPx = 0f

    var renderedState: ButtonState = ButtonState.IDLE_NO_FIELD
        private set

    /** Acessores somente-leitura para testes instrumentados do modo-arrasto travado. */
    val dragLockedForTest: Boolean get() = dragLocked
    val dragKindForTest: OrbDragKind? get() = dragKind

    private var sizePx: Int = orbSizePx

    /** Redimensiona a orbe ao vivo (slider da aba Sobrepor). A janela é WRAP_CONTENT → remede ao atualizar. */
    fun setOrbSizePx(px: Int) {
        if (px == sizePx || px <= 0) return
        sizePx = px
        currentOrbPack?.prepare(orbUi.state.toPackState(), px)
        requestLayout()
        windowParams?.let { updateWindowLayout?.invoke(it) }
    }

    private var orbUi by mutableStateOf(OrbButtonUi(OrbState.Idle, OrbCenter.None))
    private var currentOrbStyle by mutableStateOf(orbStyle)
    private var currentOrbPack by mutableStateOf(orbPack)

    /** Skin da orbe trocada com ela na tela (aba Sobrepor → Skins): redesenha sem recriar a janela. */
    fun setOrbStyle(style: OrbStyle) {
        currentOrbStyle = style
    }

    /** Pacote de skin da orbe trocado com ela na tela (ou reimportado): quem chama fecha o anterior; aqui o novo já esquenta o estado atual. */
    fun setOrbPack(pack: PackOrb?) {
        currentOrbPack = pack
        pack?.prepare(orbUi.state.toPackState(), sizePx)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingReleaseTimeout: Runnable? = null

    // Resolvedor de rajada de toques (TOGGLE): conta toques rápidos e só age quando a rajada "assenta".
    // 1 → grava; ≥3 → seletor (cai no atalho mais próximo abaixo); 2 → nada. Rajada ≥2 nunca grava.
    private var tapBurstCount = 0
    private var tapBurstRunnable: Runnable? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (renderedState == ButtonState.RECORDING) {
                val secs = elapsedSeconds()
                // Atualiza o tempo do centro E o do glifo do modo-arrasto (se travado), p/ correr mesmo
                // sem mexer o dedo enquanto grava.
                orbUi = orbUi.copy(
                    center = OrbCenter.Time(secs),
                    dragHint = orbUi.dragHint?.copy(seconds = secs),
                )
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    private var windowParams: WindowManager.LayoutParams? = null
    private var updateWindowLayout: ((WindowManager.LayoutParams) -> Unit)? = null
    private var startRawX = 0f
    private var startRawY = 0f
    private var startWindowX = 0
    private var startWindowY = 0
    private var dragging = false

    // PTT: partida adiada a partir do ocioso. Distingue "segurar para gravar" de "arrastar para mover":
    // só inicia a gravação se NÃO houver um arrasto brusco logo no começo do toque (REC-05).
    private var pttStartArmed = false
    private var pttStarted = false
    private var pttSuppressedByDrag = false
    private var pendingPttStart: Runnable? = null

    init {
        addView(ComposeView(context).apply {
            // O ComposeView não trata toque — o gesto é todo do FrameLayout pai.
            setContent {
                val ui = orbUi
                androidx.compose.runtime.CompositionLocalProvider(LocalOrbPack provides currentOrbPack) {
                    VoiceOrb(
                        state = ui.state,
                        modifier = Modifier.fillMaxSize(),
                        center = ui.center,
                        dragHint = ui.dragHint,
                        style = currentOrbStyle,
                    )
                }
            }
        })
        isClickable = true
        isFocusable = false
        render(ButtonState.IDLE_NO_FIELD)
    }

    fun bindWindowLayout(
        params: WindowManager.LayoutParams,
        updateLayout: (WindowManager.LayoutParams) -> Unit,
    ) {
        windowParams = params
        updateWindowLayout = updateLayout
    }

    /**
     * Atualiza orbe + timer a partir do [ButtonState] (chamado pelo render do coordinator). O tempo em
     * si vem de [KeyFlowController.recordingAccumulatedMs]/`recordingSegmentStartMs` — o mesmo relógio
     * autoritativo que o mic do teclado já lia (`KeyFlowKeyboardView.elapsedSeconds()`). Antes a orbe
     * mantinha um relógio PRÓPRIO, derivado só de observar as transições de estado aqui — e como o
     * `OrbButtonView` é recriado de vez em quando (troca de tema/tamanho, restart do serviço), esse
     * relógio local perdia a referência e dessincronizava do teclado (achado pelo Yan, 2026-07-03: orbe
     * mostrando 4s pausada enquanto o teclado mostrava 10s). Ler a mesma fonte elimina a duplicidade.
     */
    fun render(state: ButtonState) {
        renderedState = state
        contentDescription = describe(state)
        if (state == ButtonState.RECORDING) startTicker() else stopTicker()
        // copy (não substitui): preserva dragHint/orbFraction se a orbe estiver no modo-arrasto.
        orbUi = orbUi.copy(state = orbStateFor(state), center = orbCenterFor(state))
        // Entrou num estado: a orbe de pacote já esquenta os frames dele fora da thread de desenho.
        currentOrbPack?.prepare(orbUi.state.toPackState(), sizePx)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(sizePx, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (renderedState == ButtonState.ERROR) {
                    // No estado de erro só agimos se houver destino de navegação; senão o toque é ignorado.
                    if (KeyFlowController.errorNavTarget != ErrorNavTarget.NONE) onErrorTap()
                    return true
                }
                if (renderedState == ButtonState.CANCELLING) {
                    // Descarta JÁ (sem esperar os 3s nem o threshold de rajada). Engole este gesto inteiro;
                    // o que vier depois já é toque de ocioso (encadeia atalhos). Vale p/ TOGGLE e PTT.
                    vibrateTick()
                    cancelCommitGesture = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onIntent(GestureIntent.CANCEL_COMMIT)
                    return true
                }
                vibrateTick()
                cancelReleaseTimeout()
                parent?.requestDisallowInterceptTouchEvent(true)
                startRawX = event.rawX
                startRawY = event.rawY
                startWindowX = windowParams?.x ?: 0
                startWindowY = windowParams?.y ?: 0
                dragging = false
                gestures.onDown()
                // Alternar gravando/pausado: ficar parado ~Tempo de reação+150ms trava no modo-arrasto
                // (só no Alternar — no PTT a gravação já É o hold). SENDING entra aqui pra permitir
                // cancelar a transcrição (única direção válida nesse estado é a lateral — ver
                // updateDragFeedback) e vale nos DOIS modos, já que ali não há gravação ativa disputando
                // o toque (pedido do Yan, 2026-07-03).
                if (renderedState == ButtonState.SENDING ||
                    (mode() == RecordingMode.TOGGLE &&
                        (renderedState == ButtonState.RECORDING || renderedState == ButtonState.PAUSED))
                ) {
                    scheduleDragHold()
                }
                if (mode() == RecordingMode.PUSH_TO_TALK) {
                    pttStarted = false
                    pttSuppressedByDrag = false
                    if (renderedState.isIdleState || renderedState == ButtonState.PAUSED ||
                        renderedState == ButtonState.RECORDING
                    ) {
                        // Adia a decisão p/ distinguir "segurar" de "toque": segurar além do threshold
                        // inicia/retoma/continua (re-grab + trava); toque rápido vira atalho de rajada.
                        // Em RECORDING é a janela de release — hold continua sem costura, toques = atalhos.
                        pttStartArmed = true
                        schedulePttStart()
                    } else {
                        pttStartArmed = false
                        emit(TouchSignal.DOWN)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (cancelCommitGesture) return true // gesto do descarte: ignora movimento
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                // Modo-arrasto travado: o movimento vira ação (↑ pausa, ← cancela), não reposiciona.
                if (dragLocked) {
                    updateDragFeedback(dx, dy)
                    return true
                }
                if (gestures.onMove(dx, dy) == GestureEvent.DRAG_START) {
                    dragging = true
                    cancelDragHold() // arrastou antes de travar → é reposicionamento, não trava
                    if (pttStartArmed && !pttStarted) {
                        // Arrasto brusco antes da gravação começar: não grava neste gesto (só move).
                        cancelPttStart()
                        pttStartArmed = false
                        pttSuppressedByDrag = true
                    } else {
                        emit(TouchSignal.DRAG_START) // já gravando (ou TOGGLE) → reposiciona mantendo
                    }
                }
                if (dragging) moveTo(startWindowX + dx.roundToInt(), startWindowY + dy.roundToInt())
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (cancelCommitGesture) {
                    cancelCommitGesture = false // descarte já concluído no DOWN; encerra o gesto sem efeito
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                cancelDragHold()
                val eventType = gestures.onUp()
                parent?.requestDisallowInterceptTouchEvent(false)
                when {
                    dragLocked -> {
                        vibrateTick() // soltar o hold confirma o release (quase sempre há mudança de estado)
                        val acted = resolveDragOnUp()
                        exitDragMode()
                        // PTT: travado durante a gravação. Soltar SEM ação (não chegou a nenhuma zona) =
                        // soltar parado → ENVIA (abre a janela de release, igual ao release normal do PTT).
                        if (!acted && mode() == RecordingMode.PUSH_TO_TALK && !pttStartArmed) {
                            emit(TouchSignal.UP)
                            scheduleReleaseTimeout()
                        }
                    }
                    mode() == RecordingMode.PUSH_TO_TALK -> onUpPushToTalk(eventType)
                    else -> onUpToggle(eventType)
                }
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                cancelCommitGesture = false
                cancelDragHold()
                exitDragMode()
                cancelPttStart()
                pttStartArmed = false
                pttStarted = false
                pttSuppressedByDrag = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun onUpToggle(eventType: GestureEvent?) {
        when (eventType) {
            // Hold/drag não são toques de rajada: cancelam a rajada pendente e agem na hora.
            GestureEvent.HOLD -> {
                cancelTapBurst()
                emit(TouchSignal.HOLD)
            }
            GestureEvent.DRAG_END -> {
                cancelTapBurst()
                emit(TouchSignal.DRAG_END)
                persistCurrentPosition()
            }
            // TAP e TRIPLE_TAP contam como toques da rajada; a ação só resolve quando ela assenta.
            GestureEvent.TAP, GestureEvent.TRIPLE_TAP -> if (!dragging) registerBurstTap()
            else -> Unit
        }
    }

    private fun onUpPushToTalk(eventType: GestureEvent?) {
        // Tap curto: o dedo soltou antes do hold confirmar (a gravação ainda não começou). Não grava
        // já — conta como toque de rajada para que o atalho de 3 toques seja detectado (igual TOGGLE).
        // A rajada assenta após o threshold: 1 → nada (PTT é segurar), ≥3 → seletor (Yan).
        if (pttStartArmed) {
            cancelPttStart()
            pttStartArmed = false
            if (!dragging) registerBurstTap()
            pttStarted = false
            pttSuppressedByDrag = false
            return
        }
        // Gravação já confirmada (hold) ou ciclo dentro da janela de release: fluxo PTT normal.
        when (eventType) {
            GestureEvent.DRAG_END -> {
                emit(TouchSignal.UP) // soltar encerra a fala; o arrasto só reposicionou
                persistCurrentPosition()
                scheduleReleaseTimeout()
            }
            else -> { // HOLD ou TAP: o dedo foi solto
                emit(TouchSignal.UP)
                scheduleReleaseTimeout()
            }
        }
        pttStarted = false
        pttSuppressedByDrag = false
    }

    private fun schedulePttStart() {
        cancelPttStart()
        val r = Runnable {
            if (pttStartArmed) {
                pttStartArmed = false
                pttStarted = true
                cancelTapBurst() // virou hold de gravação: descarta rajada de toques pendente
                emit(TouchSignal.DOWN) // dedo ficou parado → inicia a gravação
                // PTT estilo WhatsApp: com a gravação confirmada, trava o reposicionamento e mostra as setas.
                // Arrastar ↑ pausa, ← cancela, ↓ envia+abre; soltar parado (sem zona) envia.
                enterDragMode()
            }
        }
        pendingPttStart = r
        // Mesmo threshold da rajada do TOGGLE: adia a confirmação da gravação para que uma rajada de
        // toques curtos (atalho de 3 toques) seja reconhecida antes de começar a gravar (Yan).
        handler.postDelayed(r, tapBurstWindowMs)
    }

    private fun cancelPttStart() {
        pendingPttStart?.let { handler.removeCallbacks(it) }
        pendingPttStart = null
    }

    private fun emit(signal: TouchSignal) {
        onIntent(controller.onSignal(signal))
    }

    // ── modo-arrasto (Alternar travado) ───────────────────────────────────────

    private fun scheduleDragHold() {
        cancelDragHold()
        val r = Runnable {
            if (gestures.onHoldElapsed() == GestureEvent.HOLD) enterDragMode()
        }
        dragHoldRunnable = r
        handler.postDelayed(r, tapBurstWindowMs + HOLD_OFFSET_MS)
    }

    private fun cancelDragHold() {
        dragHoldRunnable?.let { handler.removeCallbacks(it) }
        dragHoldRunnable = null
    }

    private fun enterDragMode() {
        dragLocked = true
        dragKind = null
        dragProx = 0f
        dragHapticKind = null
        dragHapticStep = 0
        // Centro da orbe em coords ABSOLUTAS de tela (a sobreposição das setas é LAYOUT_IN_SCREEN, cobre a
        // tela física inteira) — assim casa em qualquer posição, inclusive nos cantos.
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        dragCenterX = loc[0] + sizePx / 2
        dragCenterY = loc[1] + sizePx / 2
        dragActPx = sizePx * DRAG_ACTIVATION_FRACTION
        vibrateTick() // sinaliza a trava
        // Enviando: só a direção lateral (cancelar) é válida — não mostra as setas de cima/baixo, que não
        // fazem nada nesse estado (não dá pra pausar/retomar/abrir config no meio do envio).
        val sending = renderedState == ButtonState.SENDING
        onDragArrows(dragArrows(upAlpha = if (sending) 0f else 1f, lateralAlpha = 1f, downAlpha = if (sending) 0f else 1f))
        // Glifo central do alvo ainda invisível (aparece com a proximidade).
        orbUi = orbUi.copy(dragHint = OrbDragHint(centerKind = null, centerAlpha = 0f, seconds = elapsedSeconds()))
    }

    private fun exitDragMode() {
        if (!dragLocked) return
        dragLocked = false
        dragKind = null
        dragProx = 0f
        onDragArrows(null) // some com a sobreposição das setas
        orbUi = orbUi.copy(dragHint = null)
    }

    private fun dragArrows(upAlpha: Float, lateralAlpha: Float, downAlpha: Float) = DragArrowsState(
        centerXScreen = dragCenterX,
        centerYScreen = dragCenterY,
        activationPx = dragActPx,
        upAlpha = upAlpha,
        lateralAlpha = lateralAlpha,
        downAlpha = downAlpha,
        lateralLeft = side() == OverlaySide.RIGHT,
    )

    /**
     * Atualiza o alvo central e a opacidade das setas durante o modo-arrasto. Gravando: ↑ pausa, ← cancela.
     * Pausado (proposta do usuário): ↑ cancela, ← retoma. Enviando: só ← cancela (↑/↓ ficam pinados em 0,
     * não fazem nada nesse estado). As setas começam opacas e somem ao aproximar.
     */
    private fun updateDragFeedback(dx: Float, dy: Float) {
        val act = sizePx * DRAG_ACTIVATION_FRACTION
        val destro = side() == OverlaySide.RIGHT
        val sending = renderedState == ButtonState.SENDING
        val upProx = if (sending) 0f else (-dy / act).coerceIn(0f, 1f)
        val downProx = if (sending) 0f else (dy / act).coerceIn(0f, 1f)
        // Lateral (cancelar/retomar) vai pro CENTRO: destro arrasta ←, canhoto arrasta → (longe da borda).
        val lateralProx = ((if (destro) -dx else dx) / act).coerceIn(0f, 1f)
        val paused = renderedState == ButtonState.PAUSED
        // ↑ = alternar estado (pausa/retoma); lateral = cancelar; ↓ = abrir config (mantém o estado).
        val (kind, prox) = when {
            upProx <= 0f && lateralProx <= 0f && downProx <= 0f -> null to 0f
            upProx >= lateralProx && upProx >= downProx -> (if (paused) OrbDragKind.RESUME else OrbDragKind.PAUSE) to upProx
            downProx >= lateralProx && downProx >= upProx -> OrbDragKind.CONFIG to downProx
            else -> OrbDragKind.CANCEL to lateralProx
        }
        dragKind = kind
        dragProx = prox
        applyDragHaptics(kind, prox)
        // Setas (sobreposição separada): opacas→somem ao aproximar. Enviando, cima/baixo ficam SEMPRE
        // removidas (não só invisíveis) — sem isso, mover o dedo em qualquer direção reacendia essas duas
        // com upProx/downProx pinados em 0 (1f - 0f = opaca de novo), mesmo não fazendo nada nesse estado.
        val upAlpha = if (sending) 0f else 1f - upProx
        val downAlpha = if (sending) 0f else 1f - downProx
        onDragArrows(dragArrows(upAlpha = upAlpha, lateralAlpha = 1f - lateralProx, downAlpha = downAlpha))
        // Glifo central do alvo (orbe): transparente→opaco ao aproximar.
        orbUi = orbUi.copy(dragHint = OrbDragHint(centerKind = kind, centerAlpha = prox, seconds = elapsedSeconds()))
    }

    /**
     * Retorno tátil progressivo do arrasto travado: ticks leves que sobem de degrau conforme o dedo se
     * aproxima da ação e um "click" firme ao **cruzar o limiar** (prox ≥ 1). Só toca ao subir de degrau
     * (não repete) e reseta ao trocar de direção ou afastar — assim re-aproximar volta a dar o feedback.
     */
    private fun applyDragHaptics(kind: OrbDragKind?, prox: Float) {
        val base = vibrationIntensity()
        if (base <= 0 || kind == null) {
            dragHapticKind = kind
            dragHapticStep = 0
            return
        }
        if (kind != dragHapticKind) {
            dragHapticKind = kind
            dragHapticStep = 0
        }
        val step = (prox * DRAG_HAPTIC_STEPS).toInt().coerceIn(0, DRAG_HAPTIC_STEPS)
        if (step > dragHapticStep) {
            if (step >= DRAG_HAPTIC_STEPS) {
                Haptics.tick(context, maxOf(base, 80), minMs = 32L, maxMs = 50L) // click firme de ativação
            } else {
                Haptics.tick(context, (base * step / DRAG_HAPTIC_STEPS).coerceAtLeast(8), minMs = 10L, maxMs = 18L)
            }
        }
        dragHapticStep = step
    }

    /** Resolve a ação do arrasto ao soltar. Retorna `true` se uma ação foi disparada (chegou à zona). */
    private fun resolveDragOnUp(): Boolean {
        if (dragProx < 1f) return false // só age se chegou à zona de ativação
        when (dragKind) {
            OrbDragKind.PAUSE -> onIntent(GestureIntent.PAUSE)
            OrbDragKind.RESUME -> onIntent(GestureIntent.RESUME)
            OrbDragKind.CANCEL -> onIntent(GestureIntent.CANCEL)
            OrbDragKind.CONFIG ->
                if (mode() == RecordingMode.PUSH_TO_TALK) {
                    // PTT: abre o app e MANTÉM a gravação na janela de release — re-segurar antes do
                    // threshold continua sem costura; estourar a janela envia. (Não envia na hora.)
                    onOpenConfig()
                    emit(TouchSignal.UP)
                    scheduleReleaseTimeout()
                } else {
                    onOpenConfig() // Toggle: abre mantendo o estado (segue gravando)
                }
            null -> return false
        }
        return true
    }

    /** Pulso tátil ao interagir com a orbe, na intensidade configurada (ver [Haptics]). */
    private fun vibrateTick() = Haptics.tick(context, vibrationIntensity())

    /**
     * Conta um toque da rajada (TOGGLE) e (re)agenda a resolução para daqui a [tapBurstWindowMs]. Cada novo
     * toque dentro da janela estende a rajada; a ação só dispara quando ela assenta.
     */
    private fun registerBurstTap() {
        tapBurstCount++
        tapBurstRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { resolveTapBurst() }
        tapBurstRunnable = r
        handler.postDelayed(r, tapBurstWindowMs)
    }

    /**
     * Resolve a rajada assentada: 1 toque segue o gesto do modo (grava/para/etc); 2 e 3 toques são
     * atalhos configuráveis (resolvidos por [onShortcut], que aplica o gating idle/pause e o mapeamento).
     */
    private fun resolveTapBurst() {
        val count = tapBurstCount
        tapBurstCount = 0
        tapBurstRunnable = null
        // No PTT o toque único é gesto livre (a gravação começa no hold), então 1 toque é atalho em
        // QUALQUER estado. No TOGGLE, 1 toque é o gesto nativo (parar/enviar), então só 2/3 viram atalho.
        val ptt = mode() == RecordingMode.PUSH_TO_TALK
        val pttWindow = ptt && renderedState == ButtonState.RECORDING
        when {
            count == 1 && ptt -> onShortcut(1)
            count == 1 -> emit(TouchSignal.TAP)
            count >= 2 -> onShortcut(count) // 2/3 = atalho; >3 cai na política de overflow (OverlayService)
        }
        // PTT: os atalhos na janela NÃO enviam — reabre a janela de envio (só o estouro sem toque, ou
        // re-segurar, decide o destino da gravação).
        if (pttWindow) scheduleReleaseTimeout()
    }

    private fun cancelTapBurst() {
        tapBurstRunnable?.let { handler.removeCallbacks(it) }
        tapBurstRunnable = null
        tapBurstCount = 0
    }

    private fun scheduleReleaseTimeout() {
        cancelReleaseTimeout()
        val r = Runnable { onIntent(controller.onReleaseTimeout()) }
        pendingReleaseTimeout = r
        // Janela de re-segurar = "Tempo de reação" (mesmo limiar do toque), e não um valor fixo solto.
        handler.postDelayed(r, tapBurstWindowMs)
    }

    private fun cancelReleaseTimeout() {
        pendingReleaseTimeout?.let { handler.removeCallbacks(it) }
        pendingReleaseTimeout = null
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    private fun elapsedSeconds(): Int {
        val active = if (renderedState == ButtonState.RECORDING) {
            clock() - KeyFlowController.recordingSegmentStartMs
        } else {
            0L
        }
        return ((KeyFlowController.recordingAccumulatedMs + active) / 1000L).toInt()
    }

    private fun moveTo(x: Int, y: Int) {
        val params = windowParams ?: return
        params.gravity = params.gravity.takeIf { it != 0 } ?: (Gravity.TOP or Gravity.START)
        params.x = x
        params.y = y
        updateWindowLayout?.invoke(params)
    }

    private fun persistCurrentPosition() {
        val params = windowParams ?: return
        onPositionChanged(OverlayPosition(params.x, params.y))
    }

    private fun orbStateFor(state: ButtonState): OrbState = when (state) {
        ButtonState.RECORDING -> OrbState.Listening
        ButtonState.PAUSED -> OrbState.Paused
        ButtonState.CANCELLING -> OrbState.Paused // congelado durante a janela de desfazer
        ButtonState.SENDING -> OrbState.Transcribing
        else -> OrbState.Idle
    }

    private fun orbCenterFor(state: ButtonState): OrbCenter = when (state) {
        ButtonState.RECORDING -> OrbCenter.Time(elapsedSeconds())
        ButtonState.PAUSED -> OrbCenter.PauseGlyph(elapsedSeconds())
        ButtonState.CANCELLING -> OrbCenter.CancelGlyph(elapsedSeconds()) // lixeira + tempo congelado
        ButtonState.ERROR -> OrbCenter.ErrorGlyph
        else -> OrbCenter.None
    }

    private fun describe(state: ButtonState): String = when (state) {
        ButtonState.IDLE_READY -> "KeyFlow ready"
        ButtonState.IDLE_NO_FIELD -> "KeyFlow no field"
        ButtonState.RECORDING -> "KeyFlow recording"
        ButtonState.PAUSED -> "KeyFlow paused"
        ButtonState.SENDING -> "KeyFlow sending"
        ButtonState.ERROR -> "KeyFlow error"
        ButtonState.CANCELLING -> "KeyFlow cancelling"
        ButtonState.PENDING_PASTE -> "KeyFlow pending paste"
    }

    private data class OrbButtonUi(
        val state: OrbState,
        val center: OrbCenter,
        val dragHint: OrbDragHint? = null,
    )

    private val ButtonState.isIdleState: Boolean
        get() = this == ButtonState.IDLE_READY || this == ButtonState.IDLE_NO_FIELD

    private companion object {
        const val TICK_MS = 500L
        // Janela de rajada de toques (threshold padrão; futuro: configurável pelo usuário).
        const val DEFAULT_TAP_BURST_WINDOW_MS = 250L
        // Degraus do retorno tátil progressivo do arrasto travado (último degrau = click de ativação).
        const val DRAG_HAPTIC_STEPS = 4
        // Offset do hold sobre o "Tempo de reação" (segurada = janela + 150ms).
        const val HOLD_OFFSET_MS = 150L
        // Distância (em frações do lado da orbe) para ativar o alvo do arrasto.
        const val DRAG_ACTIVATION_FRACTION = 0.7f
    }
}
