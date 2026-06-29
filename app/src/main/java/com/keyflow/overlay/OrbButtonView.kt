package com.keyflow.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.keyflow.core.RecordingMode
import com.keyflow.data.OverlayPosition
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
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
    private val onIntent: (GestureIntent) -> Unit,
    private val gestures: GestureInterpreter = GestureInterpreter(),
    private val onPositionChanged: (OverlayPosition) -> Unit = {},
    private val onErrorTap: () -> Unit = {},
    // Atalho de N toques (2/3) resolvido fora (OverlayService): mapeia para ação + gating idle/pause.
    private val onShortcut: (Int) -> Unit = {},
    // Retorno tátil (vibração) ao interagir com a orbe — lido das Settings pelo OverlayService.
    private val hapticEnabled: () -> Boolean = { false },
    orbSizePx: Int = 156,
    private val releaseWindowMs: Long = DEFAULT_RELEASE_WINDOW_MS,
    // Janela máxima entre toques de uma "rajada" de atalho (threshold configurável na aba Sobrepor).
    tapBurstWindowMs: Long = DEFAULT_TAP_BURST_WINDOW_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : FrameLayout(context) {

    // Threshold da rajada de toques, ajustável ao vivo pelo slider de Atalhos (aba Sobrepor).
    private var tapBurstWindowMs: Long = tapBurstWindowMs

    fun setTapBurstWindowMs(ms: Long) {
        if (ms > 0) tapBurstWindowMs = ms
    }

    var renderedState: ButtonState = ButtonState.IDLE_NO_FIELD
        private set

    private var sizePx: Int = orbSizePx

    /** Redimensiona a orbe ao vivo (slider da aba Sobrepor). A janela é WRAP_CONTENT → remede ao atualizar. */
    fun setOrbSizePx(px: Int) {
        if (px == sizePx || px <= 0) return
        sizePx = px
        requestLayout()
        windowParams?.let { updateWindowLayout?.invoke(it) }
    }

    private var orbUi by mutableStateOf(OrbButtonUi(OrbState.Idle, OrbCenter.None))

    private val handler = Handler(Looper.getMainLooper())
    private var pendingReleaseTimeout: Runnable? = null

    // Resolvedor de rajada de toques (TOGGLE): conta toques rápidos e só age quando a rajada "assenta".
    // 1 → grava; ≥3 → seletor (cai no atalho mais próximo abaixo); 2 → nada. Rajada ≥2 nunca grava.
    private var tapBurstCount = 0
    private var tapBurstRunnable: Runnable? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (renderedState == ButtonState.RECORDING) {
                orbUi = orbUi.copy(center = OrbCenter.Time(elapsedSeconds()))
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    // Acúmulo de tempo (espelha AudioRecorder/T18): só conta enquanto RECORDING.
    private var accumulatedMs = 0L
    private var segmentStartMs = 0L

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
                VoiceOrb(state = ui.state, modifier = Modifier.fillMaxSize(), center = ui.center)
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

    /** Atualiza orbe + timer a partir do [ButtonState] (chamado pelo render do coordinator). */
    fun render(state: ButtonState) {
        val prev = renderedState
        renderedState = state
        contentDescription = describe(state)

        when {
            state == ButtonState.RECORDING && prev != ButtonState.RECORDING && prev != ButtonState.PAUSED -> {
                accumulatedMs = 0L
                segmentStartMs = clock()
                startTicker()
            }
            state == ButtonState.RECORDING && prev == ButtonState.PAUSED -> {
                segmentStartMs = clock() // retomada
                startTicker()
            }
            state == ButtonState.PAUSED && prev == ButtonState.RECORDING -> {
                accumulatedMs += clock() - segmentStartMs // congela o tempo
                stopTicker()
            }
            state != ButtonState.RECORDING && state != ButtonState.PAUSED -> {
                accumulatedMs = 0L
                stopTicker()
            }
        }
        orbUi = OrbButtonUi(orbStateFor(state), orbCenterFor(state))
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
                    // No estado de erro só agimos se for resolvível na Voz; senão o toque é ignorado.
                    if (com.keyflow.core.KeyFlowController.errorOpensVoz) onErrorTap()
                    return true
                }
                if (hapticEnabled()) vibrateTick()
                cancelReleaseTimeout()
                parent?.requestDisallowInterceptTouchEvent(true)
                startRawX = event.rawX
                startRawY = event.rawY
                startWindowX = windowParams?.x ?: 0
                startWindowY = windowParams?.y ?: 0
                dragging = false
                gestures.onDown()
                if (mode() == RecordingMode.PUSH_TO_TALK) {
                    pttStarted = false
                    pttSuppressedByDrag = false
                    if (renderedState.isIdleState || renderedState == ButtonState.PAUSED) {
                        // Ocioso ou pausado: adia a partida (START/RESUME) para distinguir "segurar"
                        // de "arrastar p/ mover". Arrasto brusco move sem gravar / sem sair da pausa.
                        pttStartArmed = true
                        schedulePttStart()
                    } else {
                        // Janela de release (tap-rápido → pausa): resposta imediata.
                        pttStartArmed = false
                        emit(TouchSignal.DOWN)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                if (gestures.onMove(dx, dy) == GestureEvent.DRAG_START) {
                    dragging = true
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
                val eventType = gestures.onUp()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (mode() == RecordingMode.PUSH_TO_TALK) onUpPushToTalk(eventType)
                else onUpToggle(eventType)
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
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

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    /** Pulso tátil curto ao interagir com a orbe (retorno tátil configurável). */
    private fun vibrateTick() {
        val vib = vibrator?.takeIf { it.hasVibrator() } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(18L)
        }
    }

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
        when {
            count == 1 -> emit(TouchSignal.TAP)
            count >= 2 -> onShortcut(count) // 2/3 = atalho; >3 cai na política de overflow (OverlayService)
        }
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
        handler.postDelayed(r, releaseWindowMs)
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
        val active = if (renderedState == ButtonState.RECORDING) clock() - segmentStartMs else 0L
        return ((accumulatedMs + active) / 1000L).toInt()
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
        ButtonState.SENDING -> OrbState.Transcribing
        else -> OrbState.Idle
    }

    private fun orbCenterFor(state: ButtonState): OrbCenter = when (state) {
        ButtonState.RECORDING -> OrbCenter.Time(elapsedSeconds())
        ButtonState.PAUSED -> OrbCenter.PauseGlyph
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
        ButtonState.PENDING_PASTE -> "KeyFlow pending paste"
    }

    private data class OrbButtonUi(val state: OrbState, val center: OrbCenter)

    private val ButtonState.isIdleState: Boolean
        get() = this == ButtonState.IDLE_READY || this == ButtonState.IDLE_NO_FIELD

    private companion object {
        const val TICK_MS = 500L
        const val DEFAULT_RELEASE_WINDOW_MS = 350L
        // Janela de rajada de toques (threshold padrão; futuro: configurável pelo usuário).
        const val DEFAULT_TAP_BURST_WINDOW_MS = 250L
    }
}
