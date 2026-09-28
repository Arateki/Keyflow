package com.keyflow.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup.LayoutParams
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.keyflow.ime.keyboard.layout.Geometry
import com.keyflow.ime.keyboard.layout.KeyAction
import com.keyflow.ime.keyboard.layout.KeyDef
import com.keyflow.ime.keyboard.layout.KeyboardGridState
import com.keyflow.ime.keyboard.layout.KeyboardLayout
import com.keyflow.ime.keyboard.layout.LayerId
import com.keyflow.ime.keyboard.layout.PlacedKey
import com.keyflow.ime.keyboard.layout.PlacedRow
import com.keyflow.ime.keyboard.layout.RepeatSchedule

/**
 * A grade de teclas (QWERTY/`?123`) desenhada numa única `View` em `Canvas` (RND-01). O rendering é
 * **função pura** do [KeyboardGridState]/[KeyboardLayout] — nada de `View` por tecla. Mic, action row e
 * barra retraída continuam Views, intocados (fronteira `onKeyAction`, design G4).
 *
 * Multitouch por `pointerId` (RND-05): cada dedo tem sua própria tecla pressionada + preview, sem perder
 * o 2º toque. Repeat-on-hold (RND-04) só no backspace, acelerando via [RepeatSchedule].
 *
 * [hapticFeedback]: vibra ao ENCOSTAR na tecla, com o clique de teclado do sistema (curto e afinado
 * pelo fabricante) — é o que dá a sensação de resposta imediata.
 */
class KeyboardGridView(
    context: Context,
    layout: KeyboardLayout,
    dark: Boolean,
    private val onKeyAction: (KeyAction) -> Unit,
    private val scale: Float = 1f,
    initialLayer: LayerId = LayerId.LETTERS,
    private val hapticFeedback: Boolean = true,
) : View(context) {

    private var state = KeyboardGridState(layout, initialLayer = initialLayer)

    /** Camada ativa exposta só para teste (NUM-01: confirmar que o campo numérico trocou de fato). */
    val currentLayerIdForTest: LayerId get() = state.currentLayerId

    private val kbKey = if (dark) 0xFF3A332A.toInt() else 0xFFFBF6E6.toInt()
    private val kbKeyBorder = if (dark) 0xFF4A4234.toInt() else 0xFFD7C9A0.toInt()
    private val kbModifier = if (dark) 0xFF2B2720.toInt() else 0xFFCFC096.toInt()
    private val kbInk = if (dark) 0xFFF1E7C9.toInt() else 0xFF2B2720.toInt()
    private val pressedOverlay = 0x33000000

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1)
        color = kbKeyBorder
    }
    // Ícone do globo desenhado (não emoji — 🌐 é um glifo colorido que ignora Paint.color e destoa da
    // paleta monocromática das outras teclas; pedido do Yan, 2026-07-03).
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(15)
        color = kbInk
    }
    private val previewTextPaint = Paint(textPaint)
    // Hint pequeno de dígito no canto superior direito (estilo Gboard — NUMROW-01, pedido do Yan,
    // 2026-07-03), só desenhado quando `KeyAction.Char.digitHint` não é nulo (long-press ainda ativo).
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = dp(9)
        color = kbInk
        alpha = 150
    }
    private val cornerRadius = dp(7)
    private val keyMargin = dp(3)

    private var placedRows: List<PlacedRow> = emptyList()

    /** Geometria atual das teclas, só para o instrumentado de integração (KC-T6) computar onde tocar. */
    val placedRowsForTest: List<PlacedRow> get() = placedRows

    // Acessibilidade (KC-T8/A11Y-01..02): cada tecla é um nó virtual (bounds + label); TalkBack anuncia
    // ao explorar por toque e o duplo-toque aciona a ação BASE (sem popup de acento).
    private val accessibilityHelper = KeyboardAccessibilityHelper(this)

    init {
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

    // Multitouch: 1 tecla pressionada + 1 preview por dedo (pointerId). Os popups de preview são
    // PRÉ-CRIADOS e reaproveitados (nunca desmontados em uso normal) — criar uma PopupWindow do zero a
    // cada toque leva ao menos 1 frame pra a janela ser de fato anexada, e num toque rápido o dedo solta
    // antes disso acontecer (preview nunca aparece — relatado pelo Yan no device físico, 2026-07-01).
    private val pressedKeys = mutableMapOf<Int, PlacedKey>()
    private val previewPool = mutableListOf<PreviewBubble>()
    private val activePreviews = mutableMapOf<Int, PreviewBubble>()
    private class PreviewBubble(val popup: PopupWindow, val bubble: TextView)

    // Repeat-on-hold do backspace (1 por dedo, embora só um backspace exista na grade).
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeats = mutableMapOf<Int, RepeatState>()
    private class RepeatState {
        var tick = 0
        var runnable: Runnable? = null
    }

    // Popup de acentos (long-press numa tecla com variantes — KC-T5). 1 por dedo.
    private val accentHandler = Handler(Looper.getMainLooper())
    private val accentTimers = mutableMapOf<Int, Runnable>()
    private val accentPopups = mutableMapOf<Int, AccentPopupState>()
    private class AccentPopupState(
        val popup: PopupWindow,
        val variants: List<String>,
        val chipViews: List<TextView>,
        // Retângulos em coordenadas de TELA (a popup é uma janela separada) — mesma técnica do seletor
        // de ações do mic (`KeyFlowKeyboardView.updateHighlight`).
        val chipScreenRects: List<RectF>,
        var highlightedIndex: Int = -1,
    )

    /**
     * Altura de fileira: o ideal (44dp) até o limite de [keyboardHeightFraction] da altura da tela pro
     * teclado inteiro — sem isto, a grade mantinha a MESMA altura absoluta em paisagem (bem mais baixa),
     * devorando proporcionalmente muito mais tela que em retrato (achado do Yan, 2026-07-06: "as teclas
     * estão mais altas... justamente quando temos menos espaço vertical"). Nunca encolhe abaixo de
     * [MIN_ROW_HEIGHT_DP], senão o texto da tecla (tamanho fixo, 15dp) vaza da própria tecla — achado do
     * Yan, 2026-07-07, depois de reduzir a fração de paisagem: "a letra tá quase mais alta que o espaço".
     */
    val rowHeightPx: Int
        get() {
            val metrics = resources.displayMetrics
            // O slider "Tamanho do teclado" amplifica MENOS em paisagem — sem isto, em telas curtas o
            // teclado grande sozinho já não cabia com a grade efêmera (pedido do Yan, 2026-07-07: "como
            // se o slide aumentasse menos quando no horizontal"). O PISO (abaixo) TAMBÉM usa a escala
            // amortecida agora — usando a escala cheia ali, em 130% o piso sozinho já vencia a conta do
            // `maxOf` e cancelava todo o efeito do amortecimento (achado do Yan, mesma data: "não parece
            // ter feito efeito"). O texto (tamanho fixo, 15dp × escala CHEIA) segue cabendo com folga
            // dentro do piso amortecido — MIN_ROW_HEIGHT_DP tem margem de sobra pra isso.
            val budgetScale = landscapeDampedScale(metrics, scale)
            val idealPx = 44f * metrics.density * budgetScale
            val rowCount = state.currentLayer.rows.size.coerceAtLeast(1)
            val maxBudgetPx = usableScreenHeightPx(metrics) * keyboardHeightFraction(metrics) * budgetScale
            val shrunkPx = maxOf(maxBudgetPx / rowCount, MIN_ROW_HEIGHT_DP * metrics.density * budgetScale)
            return minOf(idealPx, shrunkPx).toInt()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = state.currentLayer.rows.size * rowHeightPx
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refreshGeometry(w.toFloat())
    }

    private fun refreshGeometry(width: Float = this.width.toFloat()) {
        if (width <= 0f) return
        placedRows = Geometry.placeLayer(state.currentLayer, totalWidth = width, rowHeight = rowHeightPx.toFloat())
    }

    /**
     * Reconfigura a grade pra um [KeyboardLayout] novo (NUM-01: campo numérico ↔ texto). A View do
     * teclado é REUSADA entre campos (o service só recria em troca de idioma), então isto não pode
     * esperar por `onCreateInputView` — precisa trocar o estado em runtime, no meio da vida da View.
     * Descarta qualquer gesto em andamento (o foco do campo já mudou; um preview/popup pendente da
     * grade anterior não faz mais sentido).
     */
    fun setLayout(newLayout: KeyboardLayout, initialLayer: LayerId = LayerId.LETTERS) {
        resetTouchState()
        state = KeyboardGridState(newLayout, initialLayer = initialLayer)
        requestLayout()
        refreshGeometry()
        accessibilityHelper.invalidateRoot()
        invalidate()
    }

    private fun resetTouchState() {
        pressedKeys.keys.toList().forEach { cancelFinger(it) }
    }

    override fun onDraw(canvas: Canvas) {
        placedRows.forEach { row ->
            row.keys.forEach { placed -> drawKey(canvas, placed) }
        }
    }

    private fun drawKey(canvas: Canvas, placed: PlacedKey) {
        val bounds = RectF(
            placed.left + keyMargin,
            placed.top + keyMargin,
            placed.right - keyMargin,
            placed.bottom - keyMargin,
        )
        val isModifier = placed.keyDef.action !is KeyAction.Char && placed.keyDef.action != KeyAction.Space
        keyPaint.color = keyColorFor(placed)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, keyPaint)
        if (pressedKeys.containsValue(placed)) {
            keyPaint.color = pressedOverlay
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, keyPaint)
        }
        borderPaint.color = kbKeyBorder
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)

        val color = textColorFor(placed, isModifier)
        if (placed.keyDef.action == KeyAction.Language) {
            drawGlobeIcon(canvas, bounds, color)
        } else {
            textPaint.color = color
            val label = labelFor(placed)
            val textY = bounds.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, bounds.centerX(), textY, textPaint)
        }
        val hint = (placed.keyDef.action as? KeyAction.Char)?.digitHint
        if (hint != null) drawDigitHint(canvas, bounds, hint)
    }

    /** Número pequeno no canto superior direito (long-press ainda dá o dígito — ver [KeyAction.Char.digitHint]). */
    private fun drawDigitHint(canvas: Canvas, bounds: RectF, hint: String) {
        val x = bounds.right - dp(5)
        val y = bounds.top + dp(11)
        canvas.drawText(hint, x, y, hintPaint)
    }

    /** Círculo + equador + meridiano — globo minimalista na cor de tinta da tecla (kbInk/branco no shift). */
    private fun drawGlobeIcon(canvas: Canvas, bounds: RectF, color: Int) {
        iconPaint.color = color
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val r = minOf(bounds.width(), bounds.height()) * 0.24f
        canvas.drawCircle(cx, cy, r, iconPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, iconPaint)
        canvas.drawOval(cx - r * 0.42f, cy - r, cx + r * 0.42f, cy + r, iconPaint)
    }

    private fun keyColorFor(placed: PlacedKey): Int {
        if (placed.keyDef.action == KeyAction.Shift && state.capsLocked) return ACCENT_TEAL
        if (placed.keyDef.action == KeyAction.Shift && state.shiftActive) return ACCENT_TEAL
        val isModifier = placed.keyDef.action !is KeyAction.Char && placed.keyDef.action != KeyAction.Space
        return if (isModifier) kbModifier else kbKey
    }

    private fun textColorFor(placed: PlacedKey, isModifier: Boolean): Int {
        val shiftHighlighted = placed.keyDef.action == KeyAction.Shift && state.shiftActive
        return if (shiftHighlighted) Color.WHITE else kbInk
    }

    private fun labelFor(placed: PlacedKey): String {
        val action = placed.keyDef.action
        if (action == KeyAction.Shift) return if (state.capsLocked) "⇪" else "⇧"
        if (action is KeyAction.Char && state.shiftActive && action.text.firstOrNull()?.isLetter() == true) {
            return action.text.uppercase()
        }
        return placed.keyDef.label
    }

    @Suppress("ClickableViewAccessibility") // a11y própria chega na KC-T8 (ExploreByTouchHelper)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 1º dedo de um gesto novo: sobra de um gesto que não terminou (UP perdido) é descartada,
                // nunca digitada pelo rollover.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) resetTouchState()
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                onFingerDown(pointerId, event.getX(index), event.getY(index))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    onFingerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                onFingerUp(pointerId, event.getX(index), event.getY(index))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKeys.keys.toList().forEach { cancelFinger(it) }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun onFingerDown(pointerId: Int, x: Float, y: Float) {
        // Antes do hit-test: a tecla do dedo anterior pode trocar a camada (⇧/?123), e este toque vale
        // na camada que ficou — igual a ter soltado o outro dedo primeiro.
        pressedKeys.keys.filter { it != pointerId }.forEach { releaseEarly(it) }
        val hit = Geometry.hitTestLayer(placedRows, x, y)
        val placed = hit?.let { def -> placedRows.flatMap { it.keys }.firstOrNull { it.keyDef === def } }
        if (placed == null) return
        if (hapticFeedback) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        press(pointerId, placed)
    }

    private fun press(pointerId: Int, placed: PlacedKey) {
        pressedKeys[pointerId] = placed
        showPreview(pointerId, placed)
        val action = placed.keyDef.action
        if (action == KeyAction.Backspace) startRepeat(pointerId)
        if (action is KeyAction.Char && action.variants.isNotEmpty()) scheduleAccentPopup(pointerId, placed)
        invalidate()
    }

    /**
     * Rollover (RND-05): outro dedo encostou enquanto este segurava uma tecla — ela sai agora, na ordem
     * em que foi tocada. Sem isto, cada tecla saía só quando o SEU dedo soltava, e dois polegares
     * sobrepostos trocavam a ordem das letras. Ficam de fora o backspace (a repetição segue enquanto o
     * dedo segura) e quem está escolhendo um acento.
     */
    private fun releaseEarly(pointerId: Int) {
        val pressed = pressedKeys[pointerId] ?: return
        if (pressed.keyDef.action == KeyAction.Backspace) return
        if (accentPopups.containsKey(pointerId)) return
        pressedKeys.remove(pointerId)
        dismissPreview(pointerId)
        cancelAccentTimer(pointerId)
        activateKey(pressed.keyDef)
    }

    /** Folga para o dedo escorregar sem trocar de tecla (ver [Geometry.slideTarget]). */
    private fun slideHysteresis(key: PlacedKey): Float =
        minOf(key.width, key.bottom - key.top) * SLIDE_HYSTERESIS_FRACTION

    private fun onFingerMove(pointerId: Int, x: Float, y: Float) {
        val accent = accentPopups[pointerId]
        if (accent == null) {
            slideTo(pointerId, x, y)
            return
        }
        val screen = toScreen(x, y)
        val idx = accent.chipScreenRects.indexOfFirst { it.contains(screen.first, screen.second) }
        if (idx == accent.highlightedIndex) return
        accent.highlightedIndex = idx
        accent.chipViews.forEachIndexed { i, chip ->
            val on = i == idx
            chip.setBackgroundColor(if (on) ACCENT_TEAL else kbKey)
            chip.setTextColor(if (on) Color.WHITE else kbInk)
        }
    }

    /**
     * O dedo andou para outra tecla (além da folga): ela passa a ser a pressionada — destaque e prévia
     * acompanham, e é ela que sai ao soltar. O backspace que já está repetindo não troca.
     */
    private fun slideTo(pointerId: Int, x: Float, y: Float) {
        val current = pressedKeys[pointerId] ?: return
        val target = Geometry.slideTarget(current, placedRows, x, y, slideHysteresis(current)) ?: return
        if (target === current) return
        if (repeats[pointerId]?.let { it.tick > 0 } == true) return
        stopRepeat(pointerId)
        cancelAccentTimer(pointerId)
        press(pointerId, target)
    }

    private fun onFingerUp(pointerId: Int, x: Float, y: Float) {
        val pressed = pressedKeys.remove(pointerId)
        val wasRepeating = stopRepeat(pointerId)
        dismissPreview(pointerId)
        cancelAccentTimer(pointerId)

        val accent = accentPopups.remove(pointerId)
        if (accent != null) {
            dismissAccentPopup(accent)
            // Igual ao seletor de ações do mic: nenhum chip destacado (voltou pro botão ou soltou fora)
            // = não insere nada — o long-press "compromete" a decidir uma variante.
            val variant = accent.variants.getOrNull(accent.highlightedIndex)
            if (variant != null) onKeyAction(KeyAction.Char(state.consumeShiftFor(variant)))
            invalidate()
            return
        }

        // Repeat já emitiu o backspace continuamente; soltar não emite mais um (senão apaga 1 a mais).
        // Soltar perto da tecla (dentro da folga) ainda a digita; fora do teclado de vez, nada.
        val target = pressed?.let { Geometry.slideTarget(it, placedRows, x, y, slideHysteresis(it)) }
        if (target != null && !wasRepeating) {
            activateKey(target.keyDef)
        }
        invalidate()
    }

    /**
     * Aciona a ação BASE de uma tecla (sem popup de acento) — usado pelo toque normal e pelo clique de
     * acessibilidade (A11Y-02: TalkBack sempre usa a ação base, o popup de acento é gesto tátil).
     */
    private fun activateKey(keyDef: KeyDef) {
        val emitted = state.onKeyTapped(keyDef.action)
        refreshGeometry() // camada pode ter mudado (shift/?123)
        accessibilityHelper.invalidateRoot() // o conjunto de teclas pode ter mudado de verdade
        if (emitted != null) onKeyAction(emitted)
    }

    private fun cancelFinger(pointerId: Int) {
        pressedKeys.remove(pointerId)
        stopRepeat(pointerId)
        dismissPreview(pointerId)
        cancelAccentTimer(pointerId)
        accentPopups.remove(pointerId)?.let { dismissAccentPopup(it) }
    }

    /** Coordenada deste dedo (local à View) convertida pra TELA — sem exigir API 29 (`getRawX(index)`). */
    private fun toScreen(localX: Float, localY: Float): Pair<Float, Float> {
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        return (loc[0] + localX) to (loc[1] + localY)
    }

    // ── key preview ──────────────────────────────────────────────────────────

    private fun showPreview(pointerId: Int, placed: PlacedKey) {
        dismissPreview(pointerId)
        if (placed.keyDef.action !is KeyAction.Char) return // preview só faz sentido p/ caracteres
        val slot = acquirePreviewBubble()
        slot.bubble.text = labelFor(placed)
        slot.bubble.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        // showAsDropDown (coords relativas à ÂNCORA) em vez de showAtLocation com tela absoluta: a mesma
        // técnica já validada no seletor do mic — showAtLocation se mostrou pouco confiável em janelas de
        // IME em alguns fabricantes (ex.: Samsung/One UI), aparecendo perto do rodapé em vez da tecla.
        val xoff = ((placed.left + placed.right) / 2 - slot.bubble.measuredWidth / 2).toInt()
        val yoff = (placed.top - height - slot.bubble.measuredHeight - dp(4)).toInt()
        if (slot.popup.isShowing) {
            // Janela já anexada: só reposiciona (instantâneo) — nada de recriar. Precisa do overload
            // update(ANCHOR, x, y, w, h): o popup foi aberto com showAsDropDown (coords relativas à
            // âncora); o overload update(x, y, w, h) trata x/y como tela ABSOLUTA — misturar os dois é
            // o que fazia o popup "pular" pro canto errado e escorregar até uma posição errada.
            runCatching { slot.popup.update(this, xoff, yoff, slot.bubble.measuredWidth, slot.bubble.measuredHeight) }
        } else {
            runCatching { slot.popup.showAsDropDown(this, xoff, yoff) }
        }
        slot.bubble.visibility = View.VISIBLE
        activePreviews[pointerId] = slot
    }

    /** Reusa uma bolha do pool (sem toque nenhum usando-a agora) ou cria uma nova se todas ocupadas. */
    private fun acquirePreviewBubble(): PreviewBubble {
        previewPool.firstOrNull { it !in activePreviews.values }?.let { return it }
        val bubble = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(kbInk)
            textSize = 22f * scale
            setPadding(dp(10).toInt(), dp(6).toInt(), dp(10).toInt(), dp(6).toInt())
            background = GradientDrawable().apply {
                cornerRadius = this@KeyboardGridView.cornerRadius
                setColor(kbKey)
                setStroke(dp(1).toInt(), kbKeyBorder)
            }
        }
        val popup = PopupWindow(bubble, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = false // o gesto segue sendo da grade; o popup é só visual
            isFocusable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8)
        }
        return PreviewBubble(popup, bubble).also { previewPool.add(it) }
    }

    private fun dismissPreview(pointerId: Int) {
        val slot = activePreviews.remove(pointerId) ?: return
        // NÃO desmonta a janela (popup.dismiss() destruiria e recriaria do zero no próximo toque) — só
        // esconde o CONTEÚDO (a janela continua anexada, "quente", pro próximo showAsDropDown/update ser
        // instantâneo). Mover a janela pra fora da tela (tentativa anterior) não funciona em todo
        // fabricante: o One UI do Samsung limita a posição da popup pra dentro da tela, então ela ficava
        // "grudada" num canto em vez de sumir (relatado pelo Yan no device físico, 2026-07-01).
        slot.bubble.visibility = View.INVISIBLE
    }

    // ── popup de acentos (long-press) ────────────────────────────────────────

    private fun scheduleAccentPopup(pointerId: Int, placed: PlacedKey) {
        val runnable = Runnable {
            accentTimers.remove(pointerId)
            // Só abre se o dedo ainda estiver na MESMA tecla (não soltou/arrastou pra outra antes do hold).
            if (pressedKeys[pointerId] === placed) openAccentPopup(pointerId, placed)
        }
        accentTimers[pointerId] = runnable
        accentHandler.postDelayed(runnable, ACCENT_HOLD_MS)
    }

    private fun cancelAccentTimer(pointerId: Int) {
        accentTimers.remove(pointerId)?.let { accentHandler.removeCallbacks(it) }
    }

    private fun openAccentPopup(pointerId: Int, placed: PlacedKey) {
        dismissPreview(pointerId) // o popup de acentos substitui o preview simples
        val action = placed.keyDef.action as? KeyAction.Char ?: return
        val displayShifted = state.shiftActive
        val variants = action.variants
        val chips = variants.map { variant ->
            TextView(context).apply {
                text = if (displayShifted) variant.uppercase() else variant
                gravity = Gravity.CENTER
                setTextColor(kbInk)
                textSize = 18f * scale
                setPadding(dp(12).toInt(), dp(8).toInt(), dp(12).toInt(), dp(8).toInt())
                setBackgroundColor(kbKey)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3).toInt(), dp(3).toInt(), dp(3).toInt(), dp(3).toInt())
            background = GradientDrawable().apply {
                cornerRadius = this@KeyboardGridView.cornerRadius
                setColor(kbKey)
                setStroke(dp(1).toInt(), kbKeyBorder)
            }
            chips.forEach { addView(it) }
        }
        val popup = PopupWindow(content, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = false // o gesto segue sendo da grade — igual ao seletor de ações do mic
            isFocusable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8)
        }
        content.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        // showAsDropDown (coords relativas à ÂNCORA) — ver comentário equivalente em showPreview.
        val xoff = ((placed.left + placed.right) / 2 - content.measuredWidth / 2).toInt()
        val yoff = (placed.top - height - content.measuredHeight - dp(4)).toInt()
        runCatching { popup.showAsDropDown(this, xoff, yoff) }

        // Mede os retângulos (tela) de cada chip só depois do layout — mesma técnica do seletor do mic.
        content.post {
            val state = accentPopups[pointerId] ?: return@post
            val rects = chips.map { chip ->
                val chipLoc = IntArray(2).also { chip.getLocationOnScreen(it) }
                RectF(
                    chipLoc[0].toFloat(),
                    chipLoc[1].toFloat(),
                    (chipLoc[0] + chip.width).toFloat(),
                    (chipLoc[1] + chip.height).toFloat(),
                )
            }
            accentPopups[pointerId] = AccentPopupState(state.popup, state.variants, state.chipViews, rects)
        }

        accentPopups[pointerId] = AccentPopupState(popup, variants, chips, emptyList())
    }

    private fun dismissAccentPopup(state: AccentPopupState) {
        runCatching { state.popup.dismiss() }
    }

    // ── repeat-on-hold (backspace) ───────────────────────────────────────────

    private fun startRepeat(pointerId: Int) {
        val rs = RepeatState()
        rs.runnable = Runnable {
            onKeyAction(KeyAction.Backspace)
            rs.tick++
            repeatHandler.postDelayed(rs.runnable!!, RepeatSchedule.delayForTick(rs.tick))
        }
        repeats[pointerId] = rs
        repeatHandler.postDelayed(rs.runnable!!, RepeatSchedule.delayForTick(0))
    }

    /**
     * Para o repeat deste dedo; devolve `true` se ele já tinha **disparado** ao menos uma vez (para o UP
     * não emitir mais um backspace por cima — um toque rápido, antes do 1º tique, ainda deve apagar 1).
     */
    private fun stopRepeat(pointerId: Int): Boolean {
        val rs = repeats.remove(pointerId) ?: return false
        rs.runnable?.let { repeatHandler.removeCallbacks(it) }
        return rs.tick > 0
    }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density * scale
    private fun dp(v: Float): Float = v * resources.displayMetrics.density * scale

    // ── acessibilidade (ExploreByTouchHelper) ────────────────────────────────

    /**
     * Expõe cada [PlacedKey] hoje visível como nó virtual (bounds + label = [labelFor], `ACTION_CLICK`).
     * `inner class` de propósito: reusa `placedRows`/`labelFor`/`activateKey` do host sem duplicar estado.
     */
    private inner class KeyboardAccessibilityHelper(host: View) : ExploreByTouchHelper(host) {

        private fun allKeys(): List<PlacedKey> = placedRows.flatMap { it.keys }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val index = allKeys().indexOfFirst { it.contains(x, y) }
            return if (index >= 0) index else HOST_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds.addAll(allKeys().indices)
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val placed = allKeys().getOrNull(virtualViewId)
            if (placed == null) {
                // Nó "morto" (camada mudou entre o hover e a consulta) — vazio em vez de crashar.
                node.contentDescription = ""
                node.setBoundsInParent(Rect())
                return
            }
            node.contentDescription = labelFor(placed)
            node.setBoundsInParent(
                Rect(placed.left.toInt(), placed.top.toInt(), placed.right.toInt(), placed.bottom.toInt()),
            )
            node.className = android.widget.Button::class.java.name
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val placed = allKeys().getOrNull(virtualViewId) ?: return false
            activateKey(placed.keyDef)
            return true
        }
    }

    override fun onDetachedFromWindow() {
        // Desmonta de verdade só aqui (não a cada solta de dedo) — o pool inteiro morre junto com a View.
        previewPool.forEach { runCatching { it.popup.dismiss() } }
        previewPool.clear()
        activePreviews.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        val ACCENT_TEAL = 0xFF4E9683.toInt()
        const val ACCENT_HOLD_MS = 350L
        // Quanto da tecla (menor lado) o dedo pode escorregar para fora sem trocar de tecla. A calibrar
        // no A32: um toque rápido desliza ~1 mm, ~¼ de uma tecla em retrato.
        const val SLIDE_HYSTERESIS_FRACTION = 0.25f
        // Piso pra rowHeightPx nunca ficar menor que o texto da tecla (15dp) + folga confortável.
        const val MIN_ROW_HEIGHT_DP = 34f
    }
}

/**
 * Fração máxima da altura da tela pro teclado QWERTY inteiro (todas as fileiras) em RETRATO — na prática
 * nunca chega a ser o limitante lá (a tela sobra), então serve só de teto geral de segurança.
 */
internal const val KEYBOARD_MAX_HEIGHT_FRACTION = 0.3f

/**
 * Fração menor específica pra PAISAGEM (bem mais baixa) — reduz o quanto o teclado (barra+QWERTY) toma
 * da tela ali, deixando mais espaço pra grade efêmera (pedido do Yan, 2026-07-07: "diminui um pouco o
 * multiplicador de tamanho no horizontal pra que sobre mais espaço pra grade"). `internal`:
 * `GridSizing`/`BarView` usam o MESMO valor (via [keyboardHeightFraction]) pra estimar quanto o resto do
 * teclado reserva da tela ao dimensionar a grade — nunca ajuste um sem o outro.
 */
internal const val KEYBOARD_MAX_HEIGHT_FRACTION_LANDSCAPE = 0.2f

/** [KEYBOARD_MAX_HEIGHT_FRACTION_LANDSCAPE] em paisagem (largura > altura), senão [KEYBOARD_MAX_HEIGHT_FRACTION]. */
internal fun keyboardHeightFraction(metrics: android.util.DisplayMetrics): Float =
    if (metrics.widthPixels > metrics.heightPixels) KEYBOARD_MAX_HEIGHT_FRACTION_LANDSCAPE else KEYBOARD_MAX_HEIGHT_FRACTION

/**
 * Quanto do slider "Tamanho do teclado" acima de 100% vira altura de verdade em paisagem — só metade
 * (pedido do Yan, 2026-07-07: até 120% ficava usável, dali pra 130% piorava; "como se o slide aumentasse
 * menos quando no horizontal pra caber"). Retrato usa o valor cheio (nunca foi o problema). Só afeta o
 * teto "ideal" (o tamanho que o teclado MIRA); o piso de não-vazar-texto usa a escala real, sem
 * amortecimento — ver `rowHeightPx`.
 */
internal fun landscapeDampedScale(metrics: android.util.DisplayMetrics, scale: Float): Float =
    if (metrics.widthPixels > metrics.heightPixels) 1f + (scale - 1f) * LANDSCAPE_SCALE_DAMPING else scale

private const val LANDSCAPE_SCALE_DAMPING = 0f

/**
 * `displayMetrics.heightPixels` é a tela CRUA — a janela do IME de verdade nunca recebe isso inteiro (a
 * status bar como fica de fora, ~24dp, achado do Yan no device físico, 2026-07-07: o `Relayout` real
 * media 1017px numa tela de 1080px "crus"). Sem descontar isso, as contas de encolhimento (aqui e em
 * `GridSizing`) sempre erravam por essa mesma folga, cortando a 1ª fileira por pouco em paisagem — usa
 * SEMPRE esta função em vez de `heightPixels` direto nas contas de orçamento de altura do teclado.
 */
internal fun usableScreenHeightPx(metrics: android.util.DisplayMetrics): Float =
    (metrics.heightPixels - metrics.density * STATUS_BAR_MARGIN_DP).coerceAtLeast(0f)

private const val STATUS_BAR_MARGIN_DP = 24f
