package com.keyflow.ime.bars

import com.keyflow.ime.KeyboardColors
import com.keyflow.ime.KeyboardSkin
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup.LayoutParams
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.keyflow.core.ButtonState
import com.keyflow.ime.MicAction

/**
 * Seletor de ações do mic por hold (KBDCTL-01..04) extraído da barra antiga para a `BarView` (SYNC-02):
 * PopupWindow **não-tocável** acima do mic (o gesto continua sendo do dono do toque), chips coloridos,
 * highlight por deslize com folga generosa para baixo e cancelamento rápido ao voltar sobre o mic.
 * Lógica movida de `KeyFlowKeyboardView` (inclusive os achados do device físico de 2026-07-01: remedir
 * retângulos a cada movimento; folga só no hit, não no retângulo exposto a teste).
 */
class MicSelectorController(
    private val context: Context,
    private val dark: Boolean,
    private val scale: Float,
    skin: KeyboardSkin = KeyboardSkin.DEFAULT,
) {
    private val colors = KeyboardColors.of(skin, dark)
    private var popup: PopupWindow? = null
    private var actions: List<MicAction> = emptyList()
    private var chips: List<TextView> = emptyList()
    private var rects: List<Rect> = emptyList()
    private var highlightedIndex = -1

    private val kbKeyBorder = colors.keyBorder

    val isOpen: Boolean get() = popup != null

    /** Acessores de teste — mesmos contratos da barra antiga (`popupActionsForTest`/`popupRectsForTest`). */
    val actionsForTest: List<MicAction> get() = actions
    val rectsForTest: List<Rect> get() = rects
    val highlightedIndexForTest: Int get() = highlightedIndex

    /** Ações disponíveis por estado (vazio = hold não abre nada) — igual à barra antiga. */
    fun actionsFor(state: ButtonState): List<MicAction> = when (state) {
        ButtonState.RECORDING, ButtonState.PAUSED -> listOf(MicAction.PAUSE_RESUME, MicAction.CANCEL)
        ButtonState.CANCELLING -> listOf(MicAction.UNDO)
        // Transcrevendo: só cancelar (não há o que pausar/retomar nesse estado); Yan, 2026-07-03.
        ButtonState.SENDING -> listOf(MicAction.CANCEL)
        else -> emptyList()
    }

    /**
     * Abre o seletor acima da célula do mic. [anchor] é a view dona do toque (a `BarView`);
     * [micLeftInAnchor]/[micWidth] posicionam o popup centrado na célula do mic dentro dela.
     */
    fun open(anchor: View, micLeftInAnchor: Int, micWidth: Int, micState: ButtonState) {
        val available = actionsFor(micState)
        if (available.isEmpty() || isOpen) return

        val chipViews = available.map { action ->
            TextView(context).apply {
                text = label(action, micState)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 14f * scale
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = ripple(color(action), kbKeyBorder)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            chipViews.forEachIndexed { i, chip ->
                addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = dp(4)
                })
            }
        }

        actions = available
        chips = chipViews
        highlightedIndex = -1

        val window = PopupWindow(content, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            isTouchable = false // o gesto segue sendo do dono do toque; o popup é só visual
            isFocusable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        content.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val xoff = micLeftInAnchor + (micWidth - content.measuredWidth) / 2
        val yoff = -(anchor.height + content.measuredHeight + dp(8))
        window.showAsDropDown(anchor, xoff, yoff)
        popup = window
        content.post { rects = chipViews.map { chipRect(it) } }
    }

    /**
     * Highlight por deslize. [micTopOnScreen] é o topo da célula do mic em coordenadas de tela — voltar
     * o dedo até ele (ou abaixo) desgruda qualquer chip (cancelamento rápido, pedido do Yan 2026-07-01).
     */
    fun updateHighlight(rawX: Int, rawY: Int, micTopOnScreen: Int) {
        if (!isOpen) return
        rects = chips.map { chipRect(it) } // remede a cada movimento (popup pode assentar depois do 1º post)
        var index = rects.indexOfFirst { hit(it, rawX, rawY) }
        if (rawY >= micTopOnScreen) index = -1
        if (index == highlightedIndex) return
        highlightedIndex = index
        chips.forEachIndexed { i, chip ->
            val on = i == index
            chip.background = ripple(color(actions[i]), if (on) Color.WHITE else kbKeyBorder)
            chip.alpha = if (index == -1 || on) 1f else 0.5f
        }
    }

    /** Fecha e devolve a ação destacada (null = soltar sem escolher). */
    fun release(): MicAction? {
        val action = actions.getOrNull(highlightedIndex)
        dismiss()
        return action
    }

    fun dismiss() {
        popup?.let { runCatching { it.dismiss() } }
        popup = null
        actions = emptyList()
        chips = emptyList()
        rects = emptyList()
        highlightedIndex = -1
    }

    // ── internos (movidos da barra antiga) ───────────────────────────────────

    private fun chipRect(chip: TextView): Rect {
        val loc = IntArray(2).also { chip.getLocationOnScreen(it) }
        return Rect(loc[0], loc[1], loc[0] + chip.width, loc[1] + chip.height)
    }

    /** Folga generosa só pra BAIXO (desalinhamento de render da janela do IME visto no device). */
    private fun hit(rect: Rect, rawX: Int, rawY: Int): Boolean =
        rawX >= rect.left && rawX <= rect.right && rawY >= rect.top && rawY <= rect.bottom + dp(40)

    private fun label(action: MicAction, micState: ButtonState): String = when (action) {
        MicAction.PAUSE_RESUME -> if (micState == ButtonState.PAUSED) "▶ retomar" else "⏸ pausar"
        MicAction.CANCEL -> "✕ cancelar"
        MicAction.UNDO -> "↶ desfazer"
    }

    private fun color(action: MicAction): Int = when (action) {
        MicAction.PAUSE_RESUME -> MicVisuals.ACCENT_RECORD
        MicAction.CANCEL -> MicVisuals.ACCENT_TERRACOTTA
        MicAction.UNDO -> MicVisuals.ACCENT_TEAL
    }

    private fun ripple(bgColor: Int, borderColor: Int): RippleDrawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(7).toFloat()
            setColor(bgColor)
            setStroke(dp(1), borderColor)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(50, 0, 0, 0)), bg, null)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density * scale).toInt()
}
