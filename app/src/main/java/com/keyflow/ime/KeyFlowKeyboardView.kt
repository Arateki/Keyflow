package com.keyflow.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.keyflow.core.ButtonState

sealed interface KeyAction {
    data class Character(val char: String) : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Paste : KeyAction
    data object Escape : KeyAction
}

class KeyFlowKeyboardView(
    context: Context,
    private val onKey: (KeyAction) -> Unit,
    private val onMic: () -> Unit,
    private val onHide: () -> Unit,
    private val onExpand: () -> Unit = {},
) : LinearLayout(context) {

    private var isShifted = false
    private val charKeys = mutableListOf<TextView>()
    private var shiftKey: TextView? = null
    private var expandedMic: TextView? = null
    private var collapsedMic: TextView? = null

    private val expandedLayout = buildExpanded()
    private val collapsedBar = buildCollapsed()

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BG)
        addView(expandedLayout)
    }

    fun setExpanded(expanded: Boolean) {
        val target = if (expanded) expandedLayout else collapsedBar
        if (childCount > 0 && getChildAt(0) === target) return
        removeAllViews()
        addView(target)
    }

    fun updateMicState(state: ButtonState) {
        val (label, color) = micVisuals(state)
        listOf(expandedMic, collapsedMic).filterNotNull().forEach { v ->
            v.text = label
            v.background = rippleDrawable(color)
        }
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
        addView(keyBase("🎙", 1.5f, COLOR_MIC_NO_FIELD).also { v ->
            v.setOnClickListener { onMic() }
            expandedMic = v
        })
        addView(keyBase(",", 0.8f, COLOR_SPECIAL).also { v ->
            v.setOnClickListener { onKey(KeyAction.Character(",")) }
        })
        addView(keyBase("espaço", 4f, COLOR_KEY).also { v ->
            v.setOnClickListener { onKey(KeyAction.Space) }
        })
        addView(keyBase(".", 0.8f, COLOR_SPECIAL).also { v ->
            v.setOnClickListener { onKey(KeyAction.Character(".")) }
        })
        addView(keyBase("⏎", 1.5f, COLOR_SPECIAL).also { v ->
            v.setOnClickListener { onKey(KeyAction.Enter) }
        })
        addView(keyBase("↓", 1f, COLOR_SPECIAL).also { v ->
            v.setOnClickListener { onHide() }
        })
    }

    private fun buildCollapsed(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(COLOR_BG)
        setPadding(dp(8), dp(6), dp(8), dp(8))
        gravity = Gravity.CENTER_VERTICAL

        addView(keyBase("🎙", 1.5f, COLOR_MIC_NO_FIELD, dp(40)).also { v ->
            v.setOnClickListener { onMic() }
            collapsedMic = v
        })
        addView(keyBase("colar", 1f, COLOR_SPECIAL, dp(40)).also { v ->
            v.setOnClickListener { onKey(KeyAction.Paste) }
        })
        addView(keyBase("esc", 0.8f, COLOR_SPECIAL, dp(40)).also { v ->
            v.setOnClickListener { onKey(KeyAction.Escape) }
        })
        addView(keyBase("⏎", 0.8f, COLOR_SPECIAL, dp(40)).also { v ->
            v.setOnClickListener { onKey(KeyAction.Enter) }
        })
        addView(keyBase("⌃", 1f, COLOR_SPECIAL, dp(40)).also { v ->
            v.setOnClickListener {
                setExpanded(true)
                onExpand()
            }
        })
    }

    // ── key factory ──────────────────────────────────────────────────────────

    private fun keyFor(k: String): TextView = when (k) {
        "⇧" -> keyBase(k, 1.5f, COLOR_SPECIAL).also { v ->
            shiftKey = v
            v.setOnClickListener { toggleShift() }
        }
        "⌫" -> keyBase(k, 1.5f, COLOR_SPECIAL).also { v ->
            v.setOnClickListener { onKey(KeyAction.Backspace) }
            v.setOnLongClickListener { onKey(KeyAction.Backspace); true }
        }
        else -> keyBase(k, 1f, COLOR_KEY).also { v ->
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

    private fun keyBase(
        label: String,
        weight: Float,
        bgColor: Int,
        height: Int = dp(44),
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 15f
        background = rippleDrawable(bgColor)
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
        shiftKey?.background = rippleDrawable(if (isShifted) Color.WHITE else COLOR_SPECIAL)
        shiftKey?.setTextColor(if (isShifted) Color.BLACK else Color.WHITE)
        charKeys.forEach { v ->
            val base = v.tag as? String ?: return@forEach
            v.text = if (isShifted) base.uppercase() else base
        }
    }

    // ── utils ────────────────────────────────────────────────────────────────

    private fun shiftSymbol(digit: String) = when (digit) {
        "1" -> "!"; "2" -> "@"; "3" -> "#"; "4" -> "$"; "5" -> "%"
        "6" -> "^"; "7" -> "&"; "8" -> "*"; "9" -> "("; "0" -> ")"
        else -> digit
    }

    private fun micVisuals(state: ButtonState): Pair<String, Int> = when (state) {
        ButtonState.IDLE_READY     -> "🎙" to COLOR_MIC_READY
        ButtonState.IDLE_NO_FIELD  -> "🎙" to COLOR_MIC_NO_FIELD
        ButtonState.RECORDING      -> "⏹ REC" to COLOR_REC
        ButtonState.SENDING        -> "···" to COLOR_SEND
        ButtonState.ERROR          -> "!" to COLOR_ERROR
        ButtonState.PENDING_PASTE  -> "📋 PA" to COLOR_PENDING
    }

    private fun rippleDrawable(bgColor: Int): RippleDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(bgColor)
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            bg, null,
        )
    }

    private fun column(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BG)
    }

    private fun row(bottomPad: Int = dp(3)): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(2), dp(3), dp(2), bottomPad)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val COLOR_BG          = Color.rgb(38, 38, 38)
        private val COLOR_KEY         = Color.rgb(80, 80, 80)
        private val COLOR_SPECIAL     = Color.rgb(55, 55, 55)
        private val COLOR_MIC_READY   = Color.rgb(46, 125, 50)
        private val COLOR_MIC_NO_FIELD = Color.rgb(97, 97, 97)
        private val COLOR_REC         = Color.rgb(198, 40, 40)
        private val COLOR_SEND        = Color.rgb(21, 101, 192)
        private val COLOR_ERROR       = Color.rgb(230, 81, 0)
        private val COLOR_PENDING     = Color.rgb(106, 27, 154)

        val ROWS = listOf(
            "1 2 3 4 5 6 7 8 9 0".split(" "),
            "q w e r t y u i o p".split(" "),
            "a s d f g h j k l".split(" "),
            "⇧ z x c v b n m ⌫".split(" "),
        )
    }
}
