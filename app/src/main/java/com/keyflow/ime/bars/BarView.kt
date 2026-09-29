package com.keyflow.ime.bars

import com.keyflow.i18n.tr
import com.keyflow.ime.GlassSheetLocator
import com.keyflow.ime.GlassPainter
import com.keyflow.ime.KeyboardColors
import com.keyflow.ime.KeyboardSkin
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.GridTriggerMode
import com.keyflow.core.shortcuts.PhysicalKeyMap
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.ime.MicAction
import com.keyflow.ime.keyboard.CandidatesView
import com.keyflow.ime.keyboard.landscapeDampedScale
import com.keyflow.ime.keyboard.usableScreenHeightPx
import com.keyflow.ime.keys.PhysicalKeyEmitter
import com.keyflow.overlay.Haptics
import com.keyflow.ui.icons.KfIconDrawer
import com.keyflow.ui.icons.KfIconSpec
import com.keyflow.ui.icons.KfIcons
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
    // Grade efêmera ancorando/soltando (SB-T10/GRD-08): o host expande a região tocável da janela do IME
    // só enquanto o arrasto está ativo (ver KeyFlowKeyboardService.onComputeInsets).
    val onGridDragActiveChanged: (Boolean) -> Unit = {},
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
    private val skin: KeyboardSkin = KeyboardSkin.DEFAULT,
    grids: List<ShortcutGrid> = emptyList(),
    private val callbacks: BarCallbacks = BarCallbacks(),
    private val scale: Float = 1f,
    private val frozenSeconds: Int? = null,
    // Intensidade da vibração das Settings (0 = sem háptica) — tique da lente da grade (GRD-03).
    private val vibrationIntensity: Int = 60,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    // Fileiras reais do QWERTY atual (4, ou 5 com a fileira de números) — usado só pra estimar quanto o
    // resto do teclado reserva da tela ao dimensionar a grade efêmera (GridSizing). Um "pior caso" fixo
    // (5) reservava demais pra layouts de 4 fileiras, zerando a grade sem necessidade (achado do Yan,
    // 2026-07-07: "a grade não funciona de jeito nenhum" no scale máximo).
    private val qwertyRowCount: Int = 5,
    // Pior caso de barras KeyFlow empilhadas (colapsado OU expandido) — a reserva assumia sempre 1 barra
    // só; com 2+ subestimava o espaço real e a grade "roubava" espaço do QWERTY (achado do Yan, 2026-07-07).
    private val barCount: Int = 1,
    // Prévia de tamanho (aba Teclado): os botões dividem a largura que houver, sem o mínimo de toque que
    // faria a barra rolar num cartão mais estreito que a tela — as proporções ficam as do teclado real.
    private val fitWidth: Boolean = false,
) : ViewGroup(context) {

    private val declaredButtons: List<BarButton> = def.buttons

    // Há sugestões pra mostrar agora? Governa só a marcação da área vazia — a área em si é fixa.
    private var hasCandidates = false

    /**
     * Botões efetivos: a config crua, nos dois modos. A área do slot `Candidates` é **sempre reservada**
     * (pedido do Yan, 2026-09-08: "a área da sugestão deve ser sempre visível em ambos os modos com sua
     * rolagem própria de palavras internas"), para o layout não pular quando uma sugestão chega ou some
     * e para o que se monta no editor ser o que aparece no teclado.
     *
     * Antes o slot entrava sob demanda no retraído (2026-09-07) e, antes disso, era filtrado fora dele
     * sempre — o que fazia as duas barras saírem diferentes com "espelhar" ligado e uma palavra pela
     * metade perder as sugestões assim que o teclado recolhia.
     */
    private val buttons: List<BarButton>
        get() = declaredButtons
    private val gridsById: Map<String, ShortcutGrid> = grids.associateBy { it.id }
    private val knownGridIds: Set<String> = gridsById.keys

    // Paleta Ateliê (mesmos ints da grade Canvas/barra atual).
    private val colors = KeyboardColors.of(skin, dark)
    private val kbTray = colors.tray
    private val kbKeyBorder = colors.keyBorder
    private val kbModifier = colors.modifier
    private val kbInk = colors.ink
    private val pressedOverlay = 0x33000000
    // Aero: botões de vidro; o fundo da barra ([kbTray]) é transparente e a lâmina de vidro vem da view-mãe.
    private val glass = if (colors.isGlass) GlassPainter(dp(1f), dark) else null
    private val sheetLocator = GlassSheetLocator(this)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = kbKeyBorder
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(TEXT_SIZE_DP)
    }
    private val cornerRadius = dp(7f)
    private val marginH = dp(3f)
    private val marginV = dp(2f)
    private val iconDrawer = KfIconDrawer()

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
    private val micSelector = MicSelectorController(context, dark, scale, skin)
    private var holdRunnable: Runnable? = null

    // Grade efêmera (SB-T6/GRD-01..07): sessão pura + popup de lente; vivem só enquanto o dedo pressiona.
    private var gridSession: GridSession? = null
    private var gridPopup: GridOverlayPopup? = null
    private var activeTrigger: BarButton.GridTrigger? = null
    private var lastRawX = 0f
    private var lastRawY = 0f
    private val revealTick = object : Runnable {
        override fun run() {
            val session = gridSession ?: return
            handleGridSnapshot(session.snapshot(SystemClock.uptimeMillis()))
            handler.postDelayed(this, REVEAL_TICK_MS)
        }
    }

    // Alavanca direcional (SB-T7/JOY-01..04): controller puro + tick agendado pelo vencimento do repeat.
    private var joystick: JoystickController? = null
    private var joystickCenterX = 0f
    private var joystickCenterY = 0f
    private var joystickDx = 0f
    private var joystickDy = 0f
    private val joystickTick = object : Runnable {
        override fun run() {
            val controller = joystick ?: return
            controller.onTick(SystemClock.uptimeMillis()).forEach { fireJoystick(it) }
            scheduleJoystickTick()
        }
    }

    // Gesto atual.
    private var activeButton: BarGeometry.PlacedButton? = null
    private var pressedIndex = -1
    private var scrolling = false
    private var downX = 0f
    private var lastX = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Acessibilidade (SB-T10/A11Y-01..02): cada botão é um nó virtual (bounds + label); teclas avulsas
    // aceitam ACTION_CLICK (mesma ação do toque), alavanca/gatilho de grade só anunciam a limitação.
    private val accessibilityHelper = BarAccessibilityHelper(this)

    init {
        setWillNotDraw(false)
        if (declaredButtons.contains(BarButton.Candidates)) {
            candidatesView = CandidatesView(context, dark, onTap = callbacks.onCandidateTap, scale = scale, skin = skin)
                .also {
                    // A área é reservada nos dois modos; vazia, só a marcação sutil aparece (ver onDraw).
                    it.visibility = VISIBLE
                    addView(it)
                }
        }
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibilityHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        accessibilityHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    // ── acessores de teste (mesmo espírito da barra antiga) ──────────────────

    // Depende da CONFIG, não da geometria medida: uma view construída em teste e ainda não medida
    // continua sabendo o rótulo do mic (a barra antiga expunha isso pelo texto do TextView).
    val micLabelForTest: String?
        get() = if (buttons.contains(BarButton.Mic)) MicVisuals.visuals(micState, elapsedSeconds()).first else null
    val layoutForTest: BarGeometry.BarLayout get() = barLayout
    val scrollOffsetForTest: Float get() = scrollOffset
    val selectorOpenForTest: Boolean get() = micSelector.isOpen
    val popupActionsForTest: List<MicAction> get() = micSelector.actionsForTest
    val popupRectsForTest: List<Rect> get() = micSelector.rectsForTest
    val highlightedIndexForTest: Int get() = micSelector.highlightedIndexForTest
    val candidatesViewForTest: CandidatesView? get() = candidatesView
    val gridFrameForTest: GridFrame?
        get() = (gridSession?.snapshot(SystemClock.uptimeMillis())?.state as? GridSession.State.Active)?.frame
    val gridPopupShowingForTest: Boolean get() = gridPopup?.isShowing == true

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
        val has = candidates.isNotEmpty()
        if (has == hasCandidates) return
        hasCandidates = has
        // A geometria não muda mais (a área é fixa) — só a marcação da área vazia aparece ou some.
        invalidate()
    }

    // ── medida/layout ────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        barLayout = BarGeometry.layout(buttons, width.toFloat(), minUnitPx = if (fitWidth) 0f else dp(MIN_UNIT_DP))
        scrollOffset = scrollOffset.coerceIn(0f, barLayout.maxScroll)
        // Encolhe com 2+ barras empilhadas em paisagem (piso pra continuar tocável) — antes a altura era
        // FIXA, então toda a pressão de "não cabe" caía inteira na grade efêmera, a única coisa flexível
        // sem piso (pergunta do Yan, 2026-07-07: "por que a barra renderiza perfeita e a grade não?").
        val metrics = resources.displayMetrics
        val height = GridSizing.barHeightPx(metrics, usableScreenHeightPx(metrics), scale, barCount).toInt()
        candidatesPlaced()?.let { placed ->
            candidatesView?.measure(
                MeasureSpec.makeMeasureSpec(placed.width.toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, height)
        accessibilityHelper.invalidateRoot()
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
        // O canvas vai andar -scrollOffset: o reflexo precisa andar junto para seguir a lâmina parada.
        glass?.useSheet(sheetLocator.locate(), sheetLocator.dx - scrollOffset, sheetLocator.dy)
        canvas.save()
        canvas.translate(-scrollOffset, 0f)
        barLayout.buttons.forEach { placed ->
            // A faixa é uma View filha (CandidatesView) e pinta a si mesma, inclusive a marcação de
            // área vazia — desenhar aqui seria coberto por ela.
            if (placed.button != BarButton.Candidates) drawButton(canvas, placed)
        }
        canvas.restore()
    }


    private fun drawButton(canvas: Canvas, placed: BarGeometry.PlacedButton) {
        val bounds = RectF(placed.left + marginH, marginV, placed.right - marginH, height - marginV)
        val button = placed.button
        val (label, bg, ink) = visualsFor(button)

        if (glass != null) {
            glass.draw(canvas, bounds, cornerRadius, bg, pressed = placed.index == pressedIndex)
        } else {
            fillPaint.color = bg
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)
            if (placed.index == pressedIndex) {
                fillPaint.color = pressedOverlay
                canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)
            }
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
        }

        if (button == BarButton.Joystick) {
            drawJoystick(canvas, bounds, ink, active = placed.index == pressedIndex && joystick != null)
            return
        }
        val alpha = if (isOrphanTrigger(button)) 100 else 255
        if (button == BarButton.Mic) {
            drawMic(canvas, bounds, ink)
            return
        }
        val icon = BarIcons.iconFor(button, expandedMode)
        if (icon != null) {
            drawIcon(canvas, icon, bounds.centerX(), bounds.centerY(), fittedIconSizePx(bounds), ink, alpha)
            return
        }
        textPaint.color = ink
        textPaint.alpha = alpha
        textPaint.textSize = fittedTextSizePx(bounds)
        val textY = bounds.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(label, bounds.centerX(), textY, textPaint)
        textPaint.alpha = 255
    }

    /**
     * Rótulos em texto (teclas avulsas como ESC, cronômetro do mic) usavam tamanho FIXO ([TEXT_SIZE_DP]
     * na escala crua) — com 2+ barras empilhadas em paisagem a altura real do botão encolhe bem além disso
     * ([GridSizing.barHeightPx]), então o glifo vazava do botão (achado do Yan, 2026-07-07). Os ícones
     * ([fittedIconSizePx]) seguem a mesma regra. [TEXT_HEIGHT_RATIO] reproduz a proporção de hoje (15dp de
     * texto ÷ 24dp de `bounds.height()` no botão em tamanho ideal) — no caso normal (sem encolher) o
     * resultado é idêntico ao tamanho fixo anterior; só encolhe abaixo disso.
     */
    private fun fittedTextSizePx(bounds: RectF): Float =
        minOf(dp(TEXT_SIZE_DP), bounds.height() * TEXT_HEIGHT_RATIO)

    /** Lado do quadrado do ícone (grid 24): mesma regra do texto — ideal fixo, encolhe com a barra. */
    private fun fittedIconSizePx(bounds: RectF): Float =
        minOf(dp(ICON_SIZE_DP), bounds.height() * ICON_HEIGHT_RATIO, bounds.width() * 0.8f)

    private fun drawIcon(canvas: Canvas, icon: KfIconSpec, cx: Float, cy: Float, size: Float, ink: Int, alpha: Int = 255) =
        iconDrawer.draw(canvas, icon, cx, cy, size, ink, alpha, strokeWidth = icon.strokeWidth * ICON_STROKE_BOOST)

    /**
     * Mic: o ícone do estado (cápsula, ponto de gravação, pausa…) e, quando o estado tem, o texto ao lado
     * (cronômetro, "cancelado"). O conjunto é centralizado no botão como uma coisa só.
     */
    private fun drawMic(canvas: Canvas, bounds: RectF, ink: Int) {
        val glyph = BarIcons.micGlyph(micState, elapsedSeconds())
        val iconSize = fittedIconSizePx(bounds)
        val text = glyph.text
        if (text == null) {
            drawIcon(canvas, glyph.icon, bounds.centerX(), bounds.centerY(), iconSize, ink)
            return
        }
        textPaint.color = ink
        textPaint.textSize = fittedTextSizePx(bounds) * MIC_TEXT_RATIO
        val small = iconSize * MIC_TEXT_ICON_RATIO
        val gap = small * 0.18f
        val total = small + gap + textPaint.measureText(text)
        val left = bounds.centerX() - total / 2f
        drawIcon(canvas, glyph.icon, left + small / 2f, bounds.centerY(), small, ink)
        textPaint.textAlign = Paint.Align.LEFT
        val textY = bounds.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, left + small + gap, textY, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * Manche de fliperama no traço dos ícones ([KfIcons.Joystick]): base circular + bola deslocada na
     * direção do puxão (JOY-01).
     */
    private fun drawJoystick(canvas: Canvas, bounds: RectF, ink: Int, active: Boolean) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val unit = fittedIconSizePx(bounds) / 24f
        val baseRadius = 8f * unit
        val stroke = borderPaint.strokeWidth
        borderPaint.color = ink
        borderPaint.strokeWidth = KfIcons.Joystick.strokeWidth * ICON_STROKE_BOOST * unit
        canvas.drawCircle(cx, cy, baseRadius, borderPaint)
        borderPaint.strokeWidth = stroke
        borderPaint.color = kbKeyBorder
        val maxOffset = baseRadius * 0.55f
        val length = kotlin.math.hypot(joystickDx, joystickDy)
        val (ox, oy) = if (active && length > 0f) {
            val clamped = minOf(length, maxOffset) / length
            joystickDx * clamped to joystickDy * clamped
        } else {
            0f to 0f
        }
        fillPaint.color = if (active) MicVisuals.ACCENT_TEAL else ink
        canvas.drawCircle(cx + ox, cy + oy, 3f * unit, fillPaint)
    }

    private fun visualsFor(button: BarButton): Triple<String, Int, Int> = when (button) {
        BarButton.Mic -> {
            val (label, color) = MicVisuals.visuals(micState, elapsedSeconds())
            // Sem campo, o mic "descansa": no Sistema e no Aero, na cor das teclas de função (o oliva é do
            // Ateliê).
            if (color == MicVisuals.MIC_NO_FIELD && skin != KeyboardSkin.ATELIE) {
                Triple(label, kbModifier, kbInk)
            } else {
                Triple(label, color, Color.WHITE)
            }
        }
        BarButton.ConfigBtn -> Triple("⚙", kbModifier, kbInk)
        BarButton.ToggleExpand -> Triple(if (expandedMode) "↓" else "⌃", kbModifier, kbInk)
        BarButton.Paste -> Triple("", kbModifier, kbInk) // ícone desenhado, ver BarIcons
        is BarButton.SingleKey -> Triple(button.key.label, kbModifier, kbInk)
        BarButton.Joystick -> Triple("", kbModifier, kbInk)
        is BarButton.GridTrigger -> Triple(button.label, kbModifier, kbInk)
        BarButton.Candidates -> Triple("", kbTray, kbInk) // nunca desenhado (child view)
    }

    private fun isOrphanTrigger(button: BarButton): Boolean =
        button is BarButton.GridTrigger && button.gridId !in knownGridIds

    // ── acessibilidade (ExploreByTouchHelper) ────────────────────────────────

    /** Rótulo do nó virtual: o mesmo texto desenhado, com a limitação anunciada nos gestos contínuos (A11Y-02). */
    private fun a11yLabel(button: BarButton): String = when (button) {
        BarButton.Joystick -> tr("Alavanca (requer gesto deslizante)")
        is BarButton.GridTrigger -> tr("%s (requer gesto deslizante)", button.label)
        else -> visualsFor(button).first
    }

    /** Alavanca e gatilho de grade são gestos contínuos — o toque simples já não faz nada neles (`perform`). */
    private fun isA11yClickable(button: BarButton): Boolean = when (button) {
        BarButton.Joystick, is BarButton.GridTrigger, BarButton.Candidates -> false
        else -> true
    }

    /**
     * Expõe cada botão visível (exceto `Candidates`, que já é `View` filha própria) como nó virtual:
     * bounds + label + `ACTION_CLICK` (A11Y-01). Teclas avulsas emitem o atalho por duplo-toque do
     * TalkBack — o caminho acessível para atalhos (A11Y-02); alavanca/gatilho de grade só anunciam a
     * limitação, sem regredir o que a barra antiga já oferecia.
     */
    private inner class BarAccessibilityHelper(host: View) : ExploreByTouchHelper(host) {

        private fun allButtons(): List<BarGeometry.PlacedButton> =
            barLayout.buttons.filter { it.button != BarButton.Candidates }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val index = allButtons().indexOfFirst { it.contains(x + scrollOffset) }
            return if (index >= 0) index else HOST_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds.addAll(allButtons().indices)
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val placed = allButtons().getOrNull(virtualViewId)
            if (placed == null) {
                // Nó "morto" (layout mudou entre o hover e a consulta) — vazio em vez de crashar.
                node.contentDescription = ""
                node.setBoundsInParent(Rect())
                return
            }
            node.contentDescription = a11yLabel(placed.button)
            node.setBoundsInParent(
                Rect(
                    (placed.left - scrollOffset).toInt(),
                    0,
                    (placed.right - scrollOffset).toInt(),
                    height,
                ),
            )
            node.className = android.widget.Button::class.java.name
            val clickable = isA11yClickable(placed.button)
            node.isClickable = clickable
            if (clickable) node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val placed = allButtons().getOrNull(virtualViewId) ?: return false
            if (!isA11yClickable(placed.button)) return false
            perform(placed.button)
            return true
        }
    }

    private fun elapsedSeconds(): Int {
        frozenSeconds?.let { return it }
        val active =
            if (micState == ButtonState.RECORDING) clock() - KeyFlowController.recordingSegmentStartMs else 0L
        return ((KeyFlowController.recordingAccumulatedMs + active) / 1000L).toInt()
    }

    // ── toque ────────────────────────────────────────────────────────────────

    @Suppress("ClickableViewAccessibility") // a11y própria (ExploreByTouchHelper, ver mais abaixo)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastX = event.x
                scrolling = false
                val placed = barLayout.buttonAt(event.x + scrollOffset)
                activeButton = placed
                pressedIndex = placed?.index ?: -1
                when (val button = placed?.button) {
                    BarButton.Mic -> scheduleHold(placed)
                    is BarButton.GridTrigger -> startGridSession(button, placed, event)
                    BarButton.Joystick -> startJoystick(placed)
                    else -> Unit
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (micSelector.isOpen) {
                    micSelector.updateHighlight(event.rawX.toInt(), event.rawY.toInt(), micTopOnScreen())
                    return true
                }
                gridSession?.let { session ->
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    handleGridSnapshot(session.onMove(event.rawX, event.rawY, SystemClock.uptimeMillis()))
                    return true
                }
                joystick?.let { controller ->
                    joystickDx = event.x - joystickCenterX
                    joystickDy = event.y - joystickCenterY
                    controller.onMove(joystickDx, joystickDy, SystemClock.uptimeMillis())
                        .forEach { fireJoystick(it) }
                    scheduleJoystickTick()
                    invalidate()
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
                } else if (gridSession != null) {
                    finishGridSession(event.rawX, event.rawY)
                } else if (joystick != null) {
                    stopJoystick()
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
                dismissGridSession()
                stopJoystick()
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
            if (KeyFlowController.errorNavTarget != com.keyflow.core.ErrorNavTarget.NONE) callbacks.onErrorTap()
        } else {
            callbacks.onMic()
        }
    }

    // ── grade efêmera (SB-T6) ────────────────────────────────────────────────

    /** DOWN num gatilho válido: nasce a sessão (Pending até o 1º arrasto ancorar). Órfão = inerte. */
    private fun startGridSession(trigger: BarButton.GridTrigger, placed: BarGeometry.PlacedButton, event: MotionEvent) {
        val grid = gridsById[trigger.gridId] ?: return
        val metrics = resources.displayMetrics
        val triggerRect = buttonScreenRectForTest(placed.index)
        // A tela ÚTIL da janela do IME é menor que `heightPixels` cru (a status bar fica de fora, ~24dp
        // — achado do Yan no device físico, 2026-07-07); sem descontar isso as contas sempre erravam por
        // essa mesma folga, cortando a 1ª fileira do QWERTY por pouco em paisagem.
        val usableHeightPx = usableScreenHeightPx(metrics)
        // Encolhe a célula só o necessário pra grade inteira caber na tela (SB-T10) — sem isto, uma grade
        // grande numa tela curta (paisagem) vazava e podia estourar a janela do IME. O tamanho IDEAL da
        // célula usa a escala amortecida (não `dp()`, que é a escala crua) — sem isto, a grade continuava
        // crescendo com o slider acima de 100% em paisagem mesmo com o teclado já congelado no tamanho de
        // 100% (achado do Yan, 2026-07-07: "a diferença do tamanho da grade tá grande").
        val budgetScale = landscapeDampedScale(metrics, scale)
        val (cellWidthPx, cellHeightPx) = GridSizing.fittedCellPx(
            rows = grid.rows,
            cols = grid.cols,
            idealCellWidthPx = GRID_CELL_W_DP * metrics.density * budgetScale,
            idealCellHeightPx = GRID_CELL_H_DP * metrics.density * budgetScale,
            screenWidthPx = metrics.widthPixels.toFloat(),
            screenHeightPx = usableHeightPx,
            // O slack e a faixa de preview entram no orçamento (não somados por fora) e usam a MESMA
            // escala amortecida — precisa bater com o mesmo cálculo do overscan no KeyFlowKeyboardView,
            // senão a grade desenha maior do que a janela reservou (ou continua encolhendo com o slider
            // mesmo com o teclado já congelado — achado do Yan, 2026-07-07).
            reservedHeightPx = GridSizing.previewSpacePx(metrics, scale) + GridSizing.keyboardContentReservePx(
                metrics = metrics,
                usableHeightPx = usableHeightPx,
                scale = scale,
                qwertyRowCount = qwertyRowCount,
                barCount = barCount,
            ) + GridSizing.overscanSlackPx(metrics, scale),
        )
        callbacks.onGridDragActiveChanged(true)
        activeTrigger = trigger
        lastRawX = event.rawX
        lastRawY = event.rawY
        gridSession = GridSession(
            grid = grid,
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
            screenWidthPx = metrics.widthPixels.toFloat(),
            screenHeightPx = usableHeightPx,
            originX = event.rawX,
            originY = event.rawY,
            triggerFrame = GridFrame(
                triggerRect.left.toFloat(),
                triggerRect.top.toFloat(),
                triggerRect.width().toFloat(),
                triggerRect.height().toFloat(),
            ),
            anchorThresholdPx = dp(GRID_ANCHOR_THRESHOLD_DP),
        )
        gridPopup = GridOverlayPopup(
            context = context,
            dark = dark,
            skin = skin,
            grid = grid,
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
            scale = scale,
        )
        handler.postDelayed(revealTick, REVEAL_TICK_MS)
    }

    private fun handleGridSnapshot(snapshot: GridSession.Snapshot) {
        val state = snapshot.state as? GridSession.State.Active ?: return
        gridPopup?.let { popup ->
            if (!popup.isShowing) popup.show(anchor = this, frame = state.frame)
            popup.update(state.frame, state.cell, state.revealed, lastRawX, lastRawY)
        }
        if (snapshot.cellChanged) Haptics.tick(context, vibrationIntensity)
    }

    /** UP: emite conforme o modo do binding (GRD-04) ou cancela (GRD-05/célula vazia). */
    private fun finishGridSession(rawX: Float, rawY: Float) {
        val session = gridSession ?: return
        val trigger = activeTrigger
        val result = session.onUp(rawX, rawY)
        dismissGridSession()
        if (result is GridSession.Result.Emit && trigger != null) {
            val key = result.cell.key
            when (trigger.mode) {
                GridTriggerMode.OPEN_ONLY -> callbacks.onShortcut(key)
                // Sequência = duas emissões separadas (prefixo como chord completo, depois a tecla) —
                // exatamente o `PhysicalKeyEmitter.emitSequence` (caso tmux).
                GridTriggerMode.SEQUENCE -> {
                    trigger.prefix?.let { callbacks.onShortcut(it) }
                    callbacks.onShortcut(key)
                }
                GridTriggerMode.CHORD -> callbacks.onShortcut(
                    trigger.prefix?.let { PhysicalKeyEmitter.chordOf(it, key) } ?: key
                )
            }
        }
    }

    private fun dismissGridSession() {
        if (gridSession != null) callbacks.onGridDragActiveChanged(false)
        handler.removeCallbacks(revealTick)
        gridPopup?.dismiss()
        gridPopup = null
        gridSession = null
        activeTrigger = null
    }

    // ── alavanca direcional (SB-T7) ──────────────────────────────────────────

    private fun startJoystick(placed: BarGeometry.PlacedButton) {
        joystickCenterX = placed.left - scrollOffset + placed.width / 2f
        joystickCenterY = height / 2f
        joystickDx = 0f
        joystickDy = 0f
        joystick = JoystickController(thresholdPx = dp(JOYSTICK_THRESHOLD_DP))
    }

    /** Seta emitida (cruzou o limiar ou repeat vencido): tecla física + tique tátil (JOY-01/02). */
    private fun fireJoystick(direction: JoystickController.Direction) {
        callbacks.onShortcut(PhysicalKeyMap.key(direction.keyCode))
        Haptics.tick(context, vibrationIntensity)
    }

    /** Agenda o próximo tick exatamente no vencimento do repeat (aceleração do RepeatSchedule). */
    private fun scheduleJoystickTick() {
        handler.removeCallbacks(joystickTick)
        val due = joystick?.nextDueMs() ?: return
        handler.postDelayed(joystickTick, (due - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun stopJoystick() {
        handler.removeCallbacks(joystickTick)
        joystick?.onUp()
        joystick = null
        joystickDx = 0f
        joystickDy = 0f
        invalidate()
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
        dismissGridSession()
        stopJoystick()
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density * scale

    companion object {
        private const val TICK_MS = 500L
        private const val HOLD_MS = 280L // mesmo limiar da barra antiga (tap abaixo, seletor acima)
        private const val MIN_UNIT_DP = 44f // alvo de toque de um botão de peso 1 (gatilho do overflow)
        // Grade efêmera (SB-T6): células generosas pra navegação às cegas; tick re-avalia o reveal.
        // internal: KeyFlowKeyboardView usa pra dimensionar o overscan da janela do IME (SB-T10/GRD-08).
        internal const val GRID_CELL_W_DP = 64f
        // internal: KeyFlowKeyboardView usa pra dimensionar o overscan da janela do IME (SB-T10/GRD-08).
        internal const val GRID_CELL_H_DP = 48f
        private const val GRID_ANCHOR_THRESHOLD_DP = 24f
        private const val REVEAL_TICK_MS = 120L
        // Alavanca: raio-morto pequeno (o botão é compacto; o flick precisa ser curto).
        private const val JOYSTICK_THRESHOLD_DP = 12f
        // Tamanho de texto no tamanho ideal (barra sem encolher) — ver fittedTextSizePx.
        private const val TEXT_SIZE_DP = 15f
        // 15dp de texto ÷ 24dp de bounds.height() (28dp de barra ideal − 2×2dp de marginV) — ver fittedTextSizePx.
        private const val TEXT_HEIGHT_RATIO = TEXT_SIZE_DP / 24f
        // Ícone no tamanho ideal (grid 24 → glifo de ~15dp, a altura do texto de antes).
        private const val ICON_SIZE_DP = 21f
        private const val ICON_HEIGHT_RATIO = ICON_SIZE_DP / 24f
        // Traço um pouco mais cheio que no app: a barra é vista de relance, em cima de cor.
        private const val ICON_STROKE_BOOST = 1.15f
        // Mic com texto ao lado (cronômetro): texto e ícone menores para caber no botão de peso 1,5.
        private const val MIC_TEXT_RATIO = 0.9f
        private const val MIC_TEXT_ICON_RATIO = 0.62f
    }
}
