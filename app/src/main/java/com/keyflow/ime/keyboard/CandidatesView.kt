package com.keyflow.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Faixa horizontal de sugestões do corretor do sistema (SPL-01/02), acima da grade. Sem corretor/sem
 * sugestões fica só sem chips, sem quebrar a digitação (edge case do spec).
 *
 * Rola na horizontal (pedido do Yan, 2026-09-07): o corretor devolve várias palavras e, na largura de
 * uma barra, as últimas ficavam fora da tela sem nenhum jeito de alcançá-las.
 *
 * A área é SEMPRE reservada, nos dois modos (Yan, 2026-09-08), para o layout não pular quando uma
 * sugestão chega ou some e para a prévia do editor bater com o teclado. Vazia, ela se anuncia com uma
 * marcação sutil ([drawEmptyHint]) — sem isso seria um vão sem explicação no meio da barra.
 */
class CandidatesView(
    context: Context,
    dark: Boolean,
    private val onTap: (String) -> Unit,
    private val scale: Float = 1f,
) : HorizontalScrollView(context) {

    private val kbTray = if (dark) 0xFF332E27.toInt() else 0xFFDCD0AC.toInt()
    private val kbInk = if (dark) 0xFFF1E7C9.toInt() else 0xFF2B2720.toInt()
    private val kbKeyBorder = if (dark) 0xFF4A4234.toInt() else 0xFFD7C9A0.toInt()

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        color = kbKeyBorder
    }

    /** Está vazia agora? (a marcação da área vazia depende disto; exposto para o instrumentado) */
    val isEmptyForTest: Boolean get() = row.childCount == 0

    /** Os chips vivem nesta fileira; o scroll é o container. */
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
    }

    init {
        setBackgroundColor(kbTray)
        setPadding(dp(6), dp(3), dp(6), dp(3))
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(row)
    }

    // Sem isto, o `onMeasure` padrão preenche o que o pai der em modo AT_MOST (wrap_content em cascata)
    // em vez de respeitar uma altura fixa — a faixa incharia bem além do previsto. HEIGHT_DP (não 44dp)
    // pra casar exatamente com a barra que divide este espaço com ela: trocar de uma pra outra não pode
    // mudar a altura do teclado. Mais baixa que as teclas normais por pedido do Yan (2026-07-01, device
    // físico): essa faixa não é uma fileira de digitação, não precisa da mesma altura de toque.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(HEIGHT_DP))
    }

    /**
     * Marcação sutil enquanto a faixa está vazia: só a linha de base, no tom da borda das teclas. Fica
     * AQUI e não no `onDraw` da BarView porque esta view pinta o próprio fundo por cima do pai — a linha
     * desenhada lá simplesmente sumia. Sem texto: viraria ruído no uso diário (escolha do Yan).
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (row.childCount > 0) return
        val y = height - paddingBottom.toFloat()
        canvas.drawLine(paddingLeft.toFloat(), y, (width - paddingRight).toFloat(), y, hintPaint)
    }

    /** Palavras hoje exibidas na faixa, só para o instrumentado (KC-T7). */
    val candidatesForTest: List<String>
        get() = (0 until row.childCount).map { (row.getChildAt(it) as TextView).text.toString() }

    fun update(candidates: List<String>) {
        row.removeAllViews()
        candidates.forEach { word ->
            row.addView(
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
        // Palavra nova começa do início da lista, não onde o dedo tinha parado na anterior.
        scrollX = 0
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density * scale).toInt()

    companion object {
        // ~2/3 da altura de uma tecla normal (44dp) — pedido do Yan pra essa faixa não ocupar tanto
        // espaço quanto uma fileira de digitação de verdade.
        const val HEIGHT_DP = 28
    }
}
