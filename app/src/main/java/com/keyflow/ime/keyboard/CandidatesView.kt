package com.keyflow.ime.keyboard

import android.content.Context
import android.view.Gravity
import android.view.View.MeasureSpec
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Faixa horizontal de sugestões do corretor do sistema (SPL-01/02), acima da grade — só existe no
 * expandido (SPL-03; o retraído não a monta). Sem corretor/sem sugestões fica só sem chips, sem quebrar
 * a digitação (edge case do spec).
 */
class CandidatesView(
    context: Context,
    dark: Boolean,
    private val onTap: (String) -> Unit,
    private val scale: Float = 1f,
) : LinearLayout(context) {

    private val kbTray = if (dark) 0xFF332E27.toInt() else 0xFFDCD0AC.toInt()
    private val kbInk = if (dark) 0xFFF1E7C9.toInt() else 0xFF2B2720.toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(kbTray)
        setPadding(dp(6), dp(3), dp(6), dp(3))
    }

    // Sem isto, o `View.onMeasure` padrão preenche o que o pai der em modo AT_MOST (LinearLayout
    // wrap_content em cascata) em vez de respeitar uma altura fixa — a faixa incharia bem além do
    // previsto. HEIGHT_DP (não 44dp) pra casar exatamente com a faixa mic/config/colapsar que divide
    // este espaço com ela (KeyFlowKeyboardView.buildTopStrip) — trocar de uma pra outra não pode mudar a
    // altura do teclado. Mais baixa que as teclas normais por pedido do Yan (2026-07-01, device físico):
    // essa faixa não é uma fileira de digitação, não precisa da mesma altura de toque.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(HEIGHT_DP))
    }

    /** Palavras hoje exibidas na faixa, só para o instrumentado (KC-T7). */
    val candidatesForTest: List<String>
        get() = (0 until childCount).map { (getChildAt(it) as TextView).text.toString() }

    fun update(candidates: List<String>) {
        removeAllViews()
        candidates.forEach { word ->
            addView(
                TextView(context).apply {
                    text = word
                    gravity = Gravity.CENTER
                    setTextColor(kbInk)
                    textSize = 14f * scale
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    isClickable = true
                    setOnClickListener { onTap(word) }
                },
            )
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density * scale).toInt()

    companion object {
        // ~2/3 da altura de uma tecla normal (44dp) — pedido do Yan pra essa faixa não ocupar tanto
        // espaço quanto uma fileira de digitação de verdade.
        const val HEIGHT_DP = 28
    }
}
