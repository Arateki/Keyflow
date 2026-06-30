package com.keyflow.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController

sealed interface KeyAction {
    data class Character(val char: String) : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Paste : KeyAction
    data object Escape : KeyAction
}

/** Ações secundárias do mic do teclado, escolhidas no seletor que abre ao segurar (estilo acento). */
enum class MicAction { PAUSE_RESUME, CANCEL, UNDO }

/**
 * Teclado QWERTY do KeyFlow reskinado na direção Ateliê (KBD-01..04). Mantém a estrutura de
 * `LinearLayout`/`TextView` (encolher/expandir, shift, dígitos, fluxo já testado — opção (a) do design)
 * e aplica a paleta "papel" (claro) ou escura. O botão de microfone mostra `m:ss` ao gravar (KBD-02),
 * acumulando só enquanto RECORDING e congelando em PAUSED.
 *
 * [frozenSeconds] força um tempo fixo no mic (sem ticker) para screenshots determinísticos na galeria.
 */
class KeyFlowKeyboardView(
    context: Context,
    private val onKey: (KeyAction) -> Unit,
    private val onMic: () -> Unit,
    // Abre o app do KeyFlow (tecla ⚙ de config).
    private val onConfig: () -> Unit = {},
    // Ação secundária escolhida no seletor por hold (pausar/retomar, cancelar, desfazer).
    private val onMicAction: (MicAction) -> Unit = {},
    private val onHide: () -> Unit,
    private val onExpand: () -> Unit = {},
    private val onErrorTap: () -> Unit = {},
    dark: Boolean = false,
    private val frozenSeconds: Int? = null,
    // Escala visual (1f = produção). A demo da aba Modo usa < 1 para um teclado menor, proporcional.
    private val scale: Float = 1f,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : LinearLayout(context) {

    private var isShifted = false
    private val charKeys = mutableListOf<TextView>()
    private var shiftKey: TextView? = null
    private var expandedMic: TextView? = null
    private var collapsedMic: TextView? = null

    // Paleta Ateliê resolvida por tema (ints Android; o View não usa Compose).
    private val kbTray = if (dark) 0xFF332E27.toInt() else 0xFFDCD0AC.toInt()
    private val kbKey = if (dark) 0xFF3A332A.toInt() else 0xFFFBF6E6.toInt()
    private val kbKeyBorder = if (dark) 0xFF4A4234.toInt() else 0xFFD7C9A0.toInt()
    private val kbModifier = if (dark) 0xFF2B2720.toInt() else 0xFFCFC096.toInt()
    private val kbInk = if (dark) 0xFFF1E7C9.toInt() else 0xFF2B2720.toInt()

    // Estado/tempo do microfone (espelha AudioRecorder/OrbButtonView: conta só em RECORDING).
    private val handler = Handler(Looper.getMainLooper())
    private var micState: ButtonState = ButtonState.IDLE_NO_FIELD
    private val ticker = object : Runnable {
        override fun run() {
            if (micState == ButtonState.RECORDING) {
                renderMic(ButtonState.RECORDING)
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    // Seletor de ações por hold no mic. Expandido: popup acima do mic. Retraído: faixa dentro da janela.
    private var actionPopup: PopupWindow? = null
    private var selectorInView: LinearLayout? = null
    private var popupActions: List<MicAction> = emptyList()
    private var popupChips: List<TextView> = emptyList()
    private var popupRects: List<android.graphics.Rect> = emptyList() // retângulo (tela) de cada chip
    private var highlightedIndex = -1
    private var holdRunnable: Runnable? = null

    private val expandedLayout = buildExpanded()
    private val collapsedBar = buildCollapsed()

    init {
        orientation = VERTICAL
        // Raiz transparente de propósito: a barra/teclado expandido têm seu próprio fundo. Assim a faixa
        // do seletor (inserida acima da barra) só "tampa" onde estão os chips; o resto mostra o app atrás.
        addView(expandedLayout)
        renderMic(ButtonState.IDLE_NO_FIELD)
    }

    /** Acessores somente-leitura para testes instrumentados (KBD-01/02). */
    val expandedForTest: Boolean get() = childCount > 0 && getChildAt(0) === expandedLayout
    val micLabelForTest: String? get() = (expandedMic ?: collapsedMic)?.text?.toString()

    fun setExpanded(expanded: Boolean) {
        val target = if (expanded) expandedLayout else collapsedBar
        if (childCount > 0 && getChildAt(0) === target) return
        removeAllViews()
        addView(target)
    }

    fun updateMicState(state: ButtonState) {
        micState = state
        // O tempo é lido ao vivo do KeyFlowController (autoritativo, vê todas as transições) em
        // elapsedSeconds() — o teclado não rastreia o tempo sozinho, evitando dessincronia.
        if (state == ButtonState.RECORDING) startTicker() else stopTicker()
        renderMic(state)
    }

    private fun renderMic(state: ButtonState) {
        val (label, color) = micVisuals(state)
        listOf(expandedMic, collapsedMic).filterNotNull().forEach { v ->
            v.text = label
            v.background = rippleDrawable(color, color)
            v.setTextColor(Color.WHITE)
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    private fun elapsedSeconds(): Int {
        frozenSeconds?.let { return it }
        val active = if (micState == ButtonState.RECORDING) clock() - KeyFlowController.recordingSegmentStartMs else 0L
        return ((KeyFlowController.recordingAccumulatedMs + active) / 1000L).toInt()
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private fun buildExpanded(): LinearLayout = column().apply {
        ROWS.forEach { row -> addView(buildRow(row)) }
        addView(buildActionRow())
    }

    private fun buildRow(keys: List<String>): LinearLayout = row().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        keys.forEach { k -> addView(keyFor(k)) }
    }

    private fun buildActionRow(): LinearLayout = row(bottomPad = dp(6)).apply {
        addView(micKey(1.5f, dp(44)).also { expandedMic = it })
        addView(specialKey("⚙", 0.9f) { onConfig() })
        addView(specialKey(",", 0.8f) { onKey(KeyAction.Character(",")) })
        addView(paperKey("espaço", 3.6f) { onKey(KeyAction.Space) })
        addView(specialKey(".", 0.8f) { onKey(KeyAction.Character(".")) })
        addView(specialKey("⏎", 1.5f) { onKey(KeyAction.Enter) })
        addView(specialKey("↓", 1f) { onHide() })
    }

    private fun buildCollapsed(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(kbTray)
        setPadding(dp(8), dp(6), dp(8), dp(8))
        gravity = Gravity.CENTER_VERTICAL

        addView(micKey(1.5f, dp(40)).also { collapsedMic = it })
        addView(specialKey("⚙", 0.8f, dp(40)) { onConfig() })
        addView(specialKey("colar", 1f, dp(40)) { onKey(KeyAction.Paste) })
        addView(specialKey("esc", 0.8f, dp(40)) { onKey(KeyAction.Escape) })
        addView(specialKey("⏎", 0.8f, dp(40)) { onKey(KeyAction.Enter) })
        addView(specialKey("⌃", 1f, dp(40)) {
            setExpanded(true)
            onExpand()
        })
    }

    // ── key factory ──────────────────────────────────────────────────────────

    private fun keyFor(k: String): TextView = when (k) {
        "⇧" -> keyBase(k, 1.5f, kbModifier, kbInk).also { v ->
            shiftKey = v
            v.setOnClickListener { toggleShift() }
        }
        "⌫" -> keyBase(k, 1.5f, kbModifier, kbInk).also { v ->
            v.setOnClickListener { onKey(KeyAction.Backspace) }
            v.setOnLongClickListener { onKey(KeyAction.Backspace); true }
        }
        else -> keyBase(k, 1f, kbKey, kbInk).also { v ->
            v.tag = k
            if (k[0].isLetter()) {
                charKeys.add(v)
                v.setOnClickListener {
                    onKey(KeyAction.Character(if (isShifted) k.uppercase() else k))
                    if (isShifted) { isShifted = false; refreshShift() }
                }
            } else {
                // dígito — shift produz símbolo
                v.setOnClickListener {
                    onKey(KeyAction.Character(if (isShifted) shiftSymbol(k) else k))
                }
            }
        }
    }

    private fun paperKey(label: String, weight: Float, onClick: () -> Unit): TextView =
        keyBase(label, weight, kbKey, kbInk).also { it.setOnClickListener { onClick() } }

    private fun specialKey(label: String, weight: Float, height: Int = dp(44), onClick: () -> Unit): TextView =
        keyBase(label, weight, kbModifier, kbInk, height).also { it.setOnClickListener { onClick() } }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun micKey(weight: Float, height: Int): TextView =
        keyBase("🎙", weight, MIC_NO_FIELD, Color.WHITE, height).also { v ->
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Agenda a abertura do seletor; se soltar antes, vira tap.
                        val r = Runnable { showActionSelector(v) }
                        holdRunnable = r
                        handler.postDelayed(r, HOLD_MS)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (selectorOpen()) updateHighlight(e.rawX.toInt(), e.rawY.toInt())
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        cancelHold()
                        if (selectorOpen()) {
                            // Seletor aberto: aciona só o chip destacado; voltar o dedo ao botão (nenhum
                            // destacado) ou soltar fora não faz nada.
                            val action = popupActions.getOrNull(highlightedIndex)
                            dismissActionSelector()
                            if (action != null) onMicAction(action)
                        } else if (isInsideView(v, e)) {
                            // Tap: só aciona ao soltar AINDA em cima do botão (arrastar pra fora cancela).
                            performMicTap()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelHold()
                        dismissActionSelector()
                        true
                    }
                    else -> false
                }
            }
        }

    private fun selectorOpen() = actionPopup != null || selectorInView != null

    private fun isInsideView(v: View, e: MotionEvent): Boolean =
        e.x >= 0f && e.x <= v.width && e.y >= 0f && e.y <= v.height

    private fun performMicTap() {
        if (micState == ButtonState.ERROR) {
            // No erro só age se for resolvível na Voz; senão ignora o toque.
            if (KeyFlowController.errorOpensVoz) onErrorTap()
        } else {
            onMic()
        }
    }

    private fun cancelHold() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
    }

    /** Ações disponíveis no seletor conforme o estado atual (vazio = hold não abre nada). */
    private fun actionsFor(state: ButtonState): List<MicAction> = when (state) {
        ButtonState.RECORDING -> listOf(MicAction.PAUSE_RESUME, MicAction.CANCEL)
        ButtonState.PAUSED -> listOf(MicAction.PAUSE_RESUME, MicAction.CANCEL)
        ButtonState.CANCELLING -> listOf(MicAction.UNDO)
        else -> emptyList()
    }

    private fun actionLabel(action: MicAction): String = when (action) {
        MicAction.PAUSE_RESUME -> if (micState == ButtonState.PAUSED) "▶ retomar" else "⏸ pausar"
        MicAction.CANCEL -> "✕ cancelar"
        MicAction.UNDO -> "↶ desfazer"
    }

    private fun actionColor(action: MicAction): Int = when (action) {
        MicAction.PAUSE_RESUME -> ACCENT_RECORD
        MicAction.CANCEL -> ACCENT_TERRACOTTA
        MicAction.UNDO -> ACCENT_TEAL
    }

    /** Abre o seletor de ações acima do mic (estilo popup de variações de acento). */
    private fun showActionSelector(anchor: View) {
        val actions = actionsFor(micState)
        if (actions.isEmpty()) return

        val chips = actions.map { action ->
            TextView(context).apply {
                text = actionLabel(action)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 14f * scale
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rippleDrawable(actionColor(action), kbKeyBorder)
            }
        }
        // Chips "soltos" (cada um já tem seu fundo arredondado colorido); sem caixa em volta.
        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            chips.forEachIndexed { i, chip ->
                addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = dp(4)
                })
            }
        }

        popupActions = actions
        popupChips = chips
        highlightedIndex = -1

        val collapsed = childCount > 0 && getChildAt(0) === collapsedBar
        if (collapsed) {
            // Retraído: a janela é curta, então um popup acima dela não recebe toque. Em vez disso,
            // inserimos o seletor como uma faixa dentro da própria janela (acima da barra) — visual e
            // toque funcionam nativamente. Sem fundo e CENTRALIZADO sobre o botão que disparou (a âncora):
            // a posição vem da geometria real do botão, então acompanha se ele for reposicionado no futuro.
            // Mede antes de inserir p/ centralizar de primeira (sem flicker). Barra no rodapé; janela cresce p/ cima.
            content.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val viewLoc = IntArray(2).also { getLocationOnScreen(it) }
            val micLoc = IntArray(2).also { anchor.getLocationOnScreen(it) }
            val micLeftInView = micLoc[0] - viewLoc[0]
            val leftMargin = (micLeftInView + anchor.width / 2 - content.measuredWidth / 2).coerceAtLeast(dp(3))
            content.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START
                setMargins(leftMargin, dp(4), 0, dp(2))
            }
            addView(content, 0)
            selectorInView = content
        } else {
            // Expandido: caixa arredondada flutuante acima do mic (mantém o visual atual).
            content.setPadding(dp(4), dp(4), dp(4), dp(4))
            content.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(kbTray)
                setStroke(dp(1), kbKeyBorder)
            }
            val popup = PopupWindow(content, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                isClippingEnabled = false
                isTouchable = false // o gesto segue sendo do mic; o popup é só visual
                isFocusable = false
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                elevation = dp(8).toFloat()
            }
            content.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            // showAsDropDown usa coords relativas à ÂNCORA: centraliza e sobe acima do mic.
            val xoff = (anchor.width - content.measuredWidth) / 2
            val yoff = -(anchor.height + content.measuredHeight + dp(8))
            popup.showAsDropDown(anchor, xoff, yoff)
            actionPopup = popup
        }
        // Mede o retângulo (tela) de cada chip após o layout, para o highlight por deslize (X e Y).
        content.post {
            popupRects = chips.map { chip ->
                val loc = IntArray(2).also { chip.getLocationOnScreen(it) }
                android.graphics.Rect(loc[0], loc[1], loc[0] + chip.width, loc[1] + chip.height)
            }
        }
    }

    private fun updateHighlight(rawX: Int, rawY: Int) {
        // Só destaca se o dedo está SOBRE um chip (X e Y): voltar pro botão abaixo não seleciona nada.
        val idx = popupRects.indexOfFirst { it.contains(rawX, rawY) }
        if (idx == highlightedIndex) return
        highlightedIndex = idx
        popupChips.forEachIndexed { i, chip ->
            val action = popupActions[i]
            val on = i == idx
            chip.background = rippleDrawable(actionColor(action), if (on) Color.WHITE else kbKeyBorder)
            chip.alpha = if (idx == -1 || on) 1f else 0.5f
        }
    }

    private fun dismissActionSelector() {
        actionPopup?.let { runCatching { it.dismiss() } }
        actionPopup = null
        selectorInView?.let { runCatching { removeView(it) } } // remove a faixa in-view (retraído)
        selectorInView = null
        popupActions = emptyList()
        popupChips = emptyList()
        popupRects = emptyList()
        highlightedIndex = -1
    }

    override fun onDetachedFromWindow() {
        cancelHold()
        dismissActionSelector()
        super.onDetachedFromWindow()
    }

    private fun keyBase(
        label: String,
        weight: Float,
        bgColor: Int,
        textColor: Int,
        height: Int = dp(44),
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(textColor)
        textSize = 15f * scale
        background = rippleDrawable(bgColor, kbKeyBorder)
        isClickable = true
        isFocusable = false
        layoutParams = LayoutParams(0, height, weight).apply {
            setMargins(dp(3), dp(2), dp(3), dp(2))
        }
    }

    // ── shift ────────────────────────────────────────────────────────────────

    private fun toggleShift() {
        isShifted = !isShifted
        refreshShift()
    }

    private fun refreshShift() {
        val shifted = isShifted
        shiftKey?.background = rippleDrawable(if (shifted) ACCENT_TEAL else kbModifier, kbKeyBorder)
        shiftKey?.setTextColor(if (shifted) Color.WHITE else kbInk)
        charKeys.forEach { v ->
            val base = v.tag as? String ?: return@forEach
            v.text = if (shifted) base.uppercase() else base
        }
    }

    // ── utils ────────────────────────────────────────────────────────────────

    private fun shiftSymbol(digit: String) = when (digit) {
        "1" -> "!"; "2" -> "@"; "3" -> "#"; "4" -> "$"; "5" -> "%"
        "6" -> "^"; "7" -> "&"; "8" -> "*"; "9" -> "("; "0" -> ")"
        else -> digit
    }

    private fun micVisuals(state: ButtonState): Pair<String, Int> = when (state) {
        ButtonState.IDLE_READY     -> "🎙" to MIC_READY
        ButtonState.IDLE_NO_FIELD  -> "🎙" to MIC_NO_FIELD
        ButtonState.RECORDING      -> "● ${formatTime(elapsedSeconds())}" to ACCENT_RECORD
        ButtonState.PAUSED         -> "⏸ ${formatTime(elapsedSeconds())}" to ACCENT_RECORD
        ButtonState.SENDING        -> "···" to ACCENT_TEAL
        ButtonState.ERROR          -> "✖" to ACCENT_TERRACOTTA
        ButtonState.CANCELLING     -> "🗑 cancelado" to ACCENT_TERRACOTTA
        ButtonState.PENDING_PASTE  -> "📋 colar" to ACCENT_TERRACOTTA
    }

    private fun formatTime(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun rippleDrawable(bgColor: Int, borderColor: Int): RippleDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(7).toFloat()
            setColor(bgColor)
            setStroke(dp(1), borderColor)
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(50, 0, 0, 0)),
            bg, null,
        )
    }

    private fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setBackgroundColor(kbTray)
    }

    private fun row(bottomPad: Int = dp(3)): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(2), dp(3), dp(2), bottomPad)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density * scale).toInt()

    companion object {
        private const val TICK_MS = 500L
        // Limiar para o hold abrir o seletor de ações (≈ long-press; abaixo disso é tap).
        private const val HOLD_MS = 280L

        // Acentos Ateliê (fixos entre claro/escuro).
        private val ACCENT_TEAL = 0xFF4E9683.toInt()
        private val ACCENT_RECORD = 0xFFC6873F.toInt()
        private val ACCENT_TERRACOTTA = 0xFFD9694B.toInt()
        private val MIC_READY = 0xFF4E9683.toInt()
        private val MIC_NO_FIELD = 0xFF9C9072.toInt()

        val ROWS = listOf(
            "1 2 3 4 5 6 7 8 9 0".split(" "),
            "q w e r t y u i o p".split(" "),
            "a s d f g h j k l".split(" "),
            "⇧ z x c v b n m ⌫".split(" "),
        )
    }
}
