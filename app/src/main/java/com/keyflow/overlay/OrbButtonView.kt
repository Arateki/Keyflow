package com.keyflow.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.keyflow.core.OrbSize
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
    orbSize: OrbSize = OrbSize.MEDIUM,
    private val releaseWindowMs: Long = DEFAULT_RELEASE_WINDOW_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : FrameLayout(context) {

    var renderedState: ButtonState = ButtonState.IDLE_NO_FIELD
        private set

    private val sizePx: Int = when (orbSize) {
        OrbSize.SMALL -> 120
        OrbSize.MEDIUM -> 156
        OrbSize.LARGE -> 200
    }

    private var orbUi by mutableStateOf(OrbButtonUi(OrbState.Idle, OrbCenter.None))

    private val handler = Handler(Looper.getMainLooper())
    private var pendingTap: Runnable? = null
    private var pendingReleaseTimeout: Runnable? = null
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
                cancelPendingTap()
                cancelReleaseTimeout()
                parent?.requestDisallowInterceptTouchEvent(true)
                startRawX = event.rawX
                startRawY = event.rawY
                startWindowX = windowParams?.x ?: 0
                startWindowY = windowParams?.y ?: 0
                dragging = false
                gestures.onDown()
                if (mode() == RecordingMode.PUSH_TO_TALK) emit(TouchSignal.DOWN)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                if (gestures.onMove(dx, dy) == GestureEvent.DRAG_START) {
                    dragging = true
                    emit(TouchSignal.DRAG_START)
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
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun onUpToggle(eventType: GestureEvent?) {
        when (eventType) {
            GestureEvent.TRIPLE_TAP -> {
                cancelPendingTap()
                emit(TouchSignal.TRIPLE_TAP)
            }
            GestureEvent.HOLD -> emit(TouchSignal.HOLD)
            GestureEvent.DRAG_END -> {
                emit(TouchSignal.DRAG_END)
                persistCurrentPosition()
            }
            GestureEvent.TAP -> if (!dragging) scheduleTap()
            else -> Unit
        }
    }

    private fun onUpPushToTalk(eventType: GestureEvent?) {
        when (eventType) {
            GestureEvent.TRIPLE_TAP -> emit(TouchSignal.TRIPLE_TAP)
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
    }

    private fun emit(signal: TouchSignal) {
        onIntent(controller.onSignal(signal))
    }

    private fun scheduleTap() {
        val r = Runnable { onIntent(controller.onSignal(TouchSignal.TAP)) }
        pendingTap = r
        handler.postDelayed(r, TAP_DELAY_MS)
    }

    private fun cancelPendingTap() {
        pendingTap?.let { handler.removeCallbacks(it) }
        pendingTap = null
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

    private companion object {
        const val TAP_DELAY_MS = 250L
        const val TICK_MS = 500L
        const val DEFAULT_RELEASE_WINDOW_MS = 350L
    }
}
