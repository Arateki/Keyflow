package com.keyflow.ime.keyboard

import com.keyflow.ime.KeyboardColors
import com.keyflow.ime.KeyboardSkin
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Faixa horizontal de sugestões do corretor (SPL-01/02, Q9), acima da grade. Sem sugestões fica só sem
 * chips, sem quebrar a digitação (edge case do spec). Cada palavra é uma etiqueta com contorno e cantos
 * arredondados (pedido do Yan, 2026-09-28), do tamanho que a barra dá.
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
    skin: KeyboardSkin = KeyboardSkin.DEFAULT,
) : HorizontalScrollView(context) {

    private val colors = KeyboardColors.of(skin, dark)
    private val chipOutline = colors.chipOutline

    private val kbTray = colors.tray
    private val kbInk = colors.ink
    private val kbKeyBorder = colors.keyBorder
    private val kbKey = colors.key

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
        // Sem margem vertical: com 3dp em cima e embaixo, mais 4dp em cada etiqueta, sobrava ~14dp de altura
        // para um texto de 14sp numa barra de 28dp — a metade de baixo das palavras ficava cortada (achado do
        // Yan no WhatsApp, 2026-09-28). A altura agora é toda da etiqueta, e o texto se ajusta a ela.
        setPadding(dp(4), 0, dp(4), 0)
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
        // A barra passa a altura exata do seu espaço; fora dela (ex.: wrap_content), [HEIGHT_DP].
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            dp(HEIGHT_DP)
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) (0 until row.childCount).forEach { styleChip(row.getChildAt(it) as TextView) }
    }

    /** Etiqueta: contorno arredondado, margem em volta e texto que cabe na altura que sobrou. */
    private fun styleChip(chip: TextView) {
        val barHeight = if (height > 0) height else dp(HEIGHT_DP)
        val marginV = maxOf(dp(2), barHeight / 8)
        val chipHeight = barHeight - 2 * marginV
        chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, chipHeight).apply {
            setMargins(dp(3), marginV, dp(3), marginV)
        }
        chip.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            minOf(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f * scale, resources.displayMetrics), chipHeight * 0.55f),
        )
        chip.background = GradientDrawable().apply {
            cornerRadius = chipHeight / 2f
            setColor(kbKey)
            setStroke(maxOf(1, dp(1)), chipOutline)
        }
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

    /** Toca no chip de [word] como o dedo faria — só para o instrumentado. */
    fun tapForTest(word: String): Boolean =
        (0 until row.childCount).map { row.getChildAt(it) as TextView }.firstOrNull { it.text == word }?.performClick() ?: false

    fun update(candidates: List<String>) {
        row.removeAllViews()
        candidates.forEach { word ->
            row.addView(
                TextView(context).apply {
                    text = word
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 1
                    setTextColor(kbInk)
                    setPadding(dp(12), 0, dp(12), 0)
                    isClickable = true
                    setOnClickListener { onTap(word) }
                    styleChip(this)
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
