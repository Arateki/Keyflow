package com.keyflow.ime.bars

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.ime.MicAction
import com.keyflow.ime.keyboard.CandidatesView
import kotlin.math.abs

/** Callbacks da barra — a `BarView` não conhece service/emitter, só avisa (fronteira estilo `onKeyAction`). */
class BarCallbacks(
    val onMic: () -> Unit = {},
    val onMicAction: (MicAction) -> Unit = {},
    val onErrorTap: () -> Unit = {},
    val onConfig: () -> Unit = {},
    val onToggleExpand: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onShortcut: (ShortcutKey) -> Unit = {},
    val onCandidateTap: (String) -> Unit = {},
)

/**
 * Uma barra do teclado desenhada em Canvas (BAR-01), irmã da `KeyboardGridView`: os botões são função
 * pura do [BarDef] + [BarGeometry]; uma única view pinta tudo (mic com estado/timer, ⚙, colar, teclas
 * avulsas, alavanca, gatilhos de grade), com estas exceções pragmáticas:
 * - **Candidates** entra como child view (`CandidatesView` reusada — SYNC-04);
 * - popups (seletor do mic, lente da grade) são `PopupWindow` — nunca desenhar fora dos limites.
 *
 * Toque (BAR-05): o dono do gesto é decidido no DOWN — botões de arrasto (mic-hold, alavanca, gatilho)
 * capturam; rolagem lateral (barra com overflow) só nasce em botões de tap ou espaço vazio.
 *
 * Gatilho de grade e alavanca são desenhados aqui desde já; as interações contínuas entram nas tasks
 * SB-T6/SB-T7. Gatilho com `gridId` órfão renderiza desabilitado (edge case da spec).
 */
class BarView(
    context: Context,
    def: BarDef,
    private val dark: Boolean,
    private val expandedMode: Boolean,
    grids: List<ShortcutGrid> = emptyList(),
    private val callbacks: BarCallbacks = BarCallbacks(),
    private val scale: Float = 1f,
    private val frozenSeconds: Int? = null,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewGroup(context) {

    // Candidates só existe no expandido (SYNC-04); no retraído o slot é filtrado fora.
    private val buttons: List<BarButton> =
        def.buttons.filter { expandedMode || it != BarButton.Candidates }
    private val knownGridIds: Set<String> = grids.map { it.id }.toSet()

    // Paleta Ateliê (mesmos ints da grade Canvas/barra atual).
    private val kbTray = if (dark) 0xFF332E27.toInt() else 0xFFDCD0AC.toInt()
    private val kbKeyBorder = if (dark) 0xFF4A4234.toInt() else 0xFFD7C9A0.toInt()
    private val kbModifier = if (dark) 0xFF2B2720.toInt() else 0xFFCFC096.toInt()
    private val kbInk = if (dark) 0xFFF1E7C9.toInt() else 0xFF2B2720.toInt()
    private val pressedOverlay = 0x33000000

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = kbKeyBorder
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
    }
    private val cornerRadius = dp(7f)
    private val marginH = dp(3f)
    private val marginV = dp(2f)

    private var barLayout = BarGeometry.BarLayout.EMPTY
    private var scrollOffset = 0f

    private var candidatesView: CandidatesView? = null

    // Estado do mic (espelha a barra antiga: tempo autoritativo do KeyFlowController; ticker só RECORDING).
    private var micState: ButtonState = ButtonState.IDLE_NO_FIELD
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (micState == ButtonState.RECORDING) {
                invalidate()
                handler.postDelayed(this, TICK_MS)
            }
        }
    }
    private val micSelector = MicSelectorController(context, dark, scale)
    private var holdRunnable: Runnable? = null

    // Gesto atual.
    private var activeButton: BarGeometry.PlacedButton? = null
    private var pressedIndex = -1
    private var scrolling = false
    private var downX = 0f
    private var lastX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        setWillNotDraw(false)
        if (buttons.contains(BarButton.Candidates)) {
            candidatesView = CandidatesView(context, dark, onTap = callbacks.onCandidateTap, scale = scale)
                .also { addView(it) }
        }
    }

    // ── acessores de teste (mesmo espírito da barra antiga) ──────────────────

    val micLabelForTest: String? get() = micPlaced()?.let { MicVisuals.visuals(micState, elapsedSeconds()).first }
    val layoutForTest: BarGeometry.BarLayout get() = barLayout
    val scrollOffsetForTest: Float get() = scrollOffset
    val selectorOpenForTest: Boolean get() = micSelector.isOpen
    val popupActionsForTest: List<MicAction> get() = micSelector.actionsForTest
    val popupRectsForTest: List<Rect> get() = micSelector.rectsForTest
    val candidatesViewForTest: CandidatesView? get() = candidatesView

    /** Retângulo (tela) de um botão — os instrumentados calculam onde tocar por aqui. */
    fun buttonScreenRectForTest(index: Int): Rect {
        val placed = barLayout.buttons[index]
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val left = loc[0] + (placed.left - scrollOffset).toInt()
        return Rect(left, loc[1], left + placed.width.toInt(), loc[1] + height)
    }

    // ── API pública (roteada pela KeyFlowKeyboardView) ───────────────────────

    fun updateMicState(state: ButtonState) {
        micState = state
        handler.removeCallbacks(ticker)
        if (state == ButtonState.RECORDING) handler.post(ticker)
        invalidate()
    }

    fun updateCandidates(candidates: List<String>) {
        candidatesView?.update(candidates)
    }

    // ── medida/layout ────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        barLayout = BarGeometry.layout(buttons, width.toFloat(), minUnitPx = dp(MIN_UNIT_DP))
        scrollOffset = scrollOffset.coerceIn(0f, barLayout.maxScroll)
        val height = dp(CandidatesView.HEIGHT_DP.toFloat()).toInt()
        candidatesPlaced()?.let { placed ->
            candidatesView?.measure(
                MeasureSpec.makeMeasureSpec(placed.width.toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        layoutCandidates()
    }

    private fun layoutCandidates() {
        val child = candidatesView ?: return
        val placed = candidatesPlaced() ?: return
        val left = (placed.left - scrollOffset).toInt()
        child.layout(left, 0, left + placed.width.toInt(), height)
    }

    private fun candidatesPlaced(): BarGeometry.PlacedButton? =
        barLayout.buttons.firstOrNull { it.button == BarButton.Candidates }

    private fun micPlaced(): BarGeometry.PlacedButton? =
        barLayout.buttons.firstOrNull { it.button == BarButton.Mic }

    // ── desenho ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(kbTray)
        canvas.save()
        canvas.translate(-scrollOffset, 0f)
        barLayout.buttons.forEach { placed ->
            if (placed.button != BarButton.Candidates) drawButton(canvas, placed)
        }
        canvas.restore()
    }

    private fun drawButton(canvas: Canvas, placed: BarGeometry.PlacedButton) {
        val bounds = RectF(placed.left + marginH, marginV, placed.right - marginH, height - marginV)
        val button = placed.button
        val (label, bg, ink) = visualsFor(button)

        fillPaint.color = bg
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)
        if (placed.index == pressedIndex) {
            fillPaint.color = pressedOverlay
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)
        }
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)

        if (button == BarButton.Joystick) {
            drawJoystick(canvas, bounds, ink)
            return
        }
        textPaint.color = ink
        textPaint.alpha = if (isOrphanTrigger(button)) 100 else 255
        val textY = bounds.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(label, bounds.centerX(), textY, textPaint)
        textPaint.alpha = 255
    }

    /** Manche de fliperama: base circular + bolinha central (deslocamento entra na SB-T7). */
    private fun drawJoystick(canvas: Canvas, bounds: RectF, ink: Int) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val baseRadius = minOf(bounds.width(), bounds.height()) / 2f - dp(4f)
        borderPaint.color = ink
        canvas.drawCircle(cx, cy, baseRadius, borderPaint)
        borderPaint.color = kbKeyBorder
        fillPaint.color = ink
        canvas.drawCircle(cx, cy, baseRadius * 0.42f, fillPaint)
    }

    private fun visualsFor(button: BarButton): Triple<String, Int, Int> = when (button) {
        BarButton.Mic -> {
            val (label, color) = MicVisuals.visuals(micState, elapsedSeconds())
            Triple(label, color, Color.WHITE)
        }
        BarButton.ConfigBtn -> Triple("⚙", kbModifier, kbInk)
        BarButton.ToggleExpand -> Triple(if (expandedMode) "↓" else "⌃", kbModifier, kbInk)
        BarButton.Paste -> Triple("colar", kbModifier, kbInk)
        is BarButton.SingleKey -> Triple(button.key.label, kbModifier, kbInk)
        BarButton.Joystick -> Triple("", kbModifier, kbInk)
        is BarButton.GridTrigger -> Triple(button.label, kbModifier, kbInk)
        BarButton.Candidates -> Triple("", kbTray, kbInk) // nunca desenhado (child view)
    }

    private fun isOrphanTrigger(button: BarButton): Boolean =
        button is BarButton.GridTrigger && button.gridId !in knownGridIds

    private fun elapsedSeconds(): Int {
        frozenSeconds?.let { return it }
        val active =
            if (micState == ButtonState.RECORDING) clock() - KeyFlowController.recordingSegmentStartMs else 0L
        return ((KeyFlowController.recordingAccumulatedMs + active) / 1000L).toInt()
    }

    // ── toque ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastX = event.x
                scrolling = false
                val placed = barLayout.buttonAt(event.x + scrollOffset)
                activeButton = placed
                pressedIndex = placed?.index ?: -1
                if (placed?.button == BarButton.Mic) scheduleHold(placed)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (micSelector.isOpen) {
                    micSelector.updateHighlight(event.rawX.toInt(), event.rawY.toInt(), micTopOnScreen())
                    return true
                }
                maybeStartScroll(event.x)
                if (scrolling) {
                    scrollOffset = (scrollOffset + (lastX - event.x)).coerceIn(0f, barLayout.maxScroll)
                    layoutCandidates()
                    invalidate()
                }
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelHold()
                if (micSelector.isOpen) {
                    micSelector.release()?.let { callbacks.onMicAction(it) }
                } else if (!scrolling) {
                    activeButton?.let { placed ->
                        if (placed.contains(event.x + scrollOffset)) perform(placed.button)
                    }
                }
                endGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                micSelector.dismiss()
                endGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Rolagem só nasce em botão de tap/espaço vazio (BAR-05); arrastáveis capturam o gesto. */
    private fun maybeStartScroll(x: Float) {
        if (scrolling || !barLayout.scrollable) return
        val draggable = activeButton?.button?.let { BarGeometry.isDraggable(it) } == true
        if (!draggable && abs(x - downX) > touchSlop) {
            scrolling = true
            cancelHold()
            pressedIndex = -1
        }
    }

    private fun perform(button: BarButton) {
        when (button) {
            BarButton.Mic -> performMicTap()
            BarButton.ConfigBtn -> callbacks.onConfig()
            BarButton.ToggleExpand -> callbacks.onToggleExpand()
            BarButton.Paste -> callbacks.onPaste()
            is BarButton.SingleKey -> callbacks.onShortcut(button.key)
            // Interações contínuas entram em SB-T6/SB-T7; tap nelas não faz nada.
            BarButton.Joystick, is BarButton.GridTrigger, BarButton.Candidates -> Unit
        }
    }

    private fun performMicTap() {
        if (micState == ButtonState.ERROR) {
            if (KeyFlowController.errorOpensVoz) callbacks.onErrorTap()
        } else {
            callbacks.onMic()
        }
    }

    private fun scheduleHold(placed: BarGeometry.PlacedButton) {
        val r = Runnable {
            micSelector.open(
                anchor = this,
                micLeftInAnchor = (placed.left - scrollOffset).toInt(),
                micWidth = placed.width.toInt(),
                micState = micState,
            )
        }
        holdRunnable = r
        handler.postDelayed(r, HOLD_MS)
    }

    private fun cancelHold() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
    }

    private fun micTopOnScreen(): Int {
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        return loc[1] + marginV.toInt()
    }

    private fun endGesture() {
        activeButton = null
        pressedIndex = -1
        scrolling = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelHold()
        micSelector.dismiss()
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density * scale

    companion object {
        private const val TICK_MS = 500L
        private const val HOLD_MS = 280L // mesmo limiar da barra antiga (tap abaixo, seletor acima)
        private const val MIN_UNIT_DP = 44f // alvo de toque de um botão de peso 1 (gatilho do overflow)
    }
}
