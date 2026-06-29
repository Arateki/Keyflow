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
import android.widget.LinearLayout
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
    private var accumulatedMs = 0L
    private var segmentStartMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (micState == ButtonState.RECORDING) {
                renderMic(ButtonState.RECORDING)
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    private val expandedLayout = buildExpanded()
    private val collapsedBar = buildCollapsed()

    init {
        orientation = VERTICAL
        setBackgroundColor(kbTray)
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
        val prev = micState
        micState = state
        when {
            state == ButtonState.RECORDING && prev != ButtonState.RECORDING && prev != ButtonState.PAUSED -> {
                accumulatedMs = 0L
                segmentStartMs = clock()
                startTicker()
            }
            state == ButtonState.RECORDING && prev == ButtonState.PAUSED -> {
                segmentStartMs = clock()
                startTicker()
            }
            state == ButtonState.PAUSED && prev == ButtonState.RECORDING -> {
                accumulatedMs += clock() - segmentStartMs
                stopTicker()
            }
            state != ButtonState.RECORDING && state != ButtonState.PAUSED -> {
                accumulatedMs = 0L
                stopTicker()
            }
        }
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
        val active = if (micState == ButtonState.RECORDING) clock() - segmentStartMs else 0L
        return ((accumulatedMs + active) / 1000L).toInt()
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
        addView(specialKey(",", 0.8f) { onKey(KeyAction.Character(",")) })
        addView(paperKey("espaço", 4f) { onKey(KeyAction.Space) })
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

    private fun micKey(weight: Float, height: Int): TextView =
        keyBase("🎙", weight, MIC_NO_FIELD, Color.WHITE, height).also { v ->
            v.setOnClickListener {
                if (micState == ButtonState.ERROR) {
                    // No erro só age se for resolvível na Voz; senão ignora o toque.
                    if (KeyFlowController.errorOpensVoz) onErrorTap()
                } else {
                    onMic()
                }
            }
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
