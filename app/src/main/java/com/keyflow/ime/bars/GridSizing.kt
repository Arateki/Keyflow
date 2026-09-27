package com.keyflow.ime.bars

import android.util.DisplayMetrics
import com.keyflow.ime.keyboard.keyboardHeightFraction
import com.keyflow.ime.keyboard.landscapeDampedScale

/**
 * Tamanho de célula da grade efêmera que garante caber na tela (SB-T10, achado do Yan, 2026-07-06): uma
 * grade 6×10 sem limite fez o espaçador de overscan da janela do IME (`KeyFlowKeyboardView.gridOverscanPx`)
 * passar da altura disponível em paisagem (bem mais baixa que retrato), sumindo o teclado inteiro. Em vez
 * de um tamanho de célula fixo, usa o ideal até o limite e encolhe só o necessário pra caber.
 */
object GridSizing {
    /**
     * Altura ideal (28dp, mesma de `CandidatesView.HEIGHT_DP`) até o limite de
     * [BAR_HEIGHT_FRACTION_LANDSCAPE] da altura útil ÷ [barCount] — igual ao piso/teto que
     * `KeyboardGridView.rowHeightPx` já aplica ao QWERTY, mas pras BARRAS Canvas. Antes as barras tinham
     * altura FIXA (nunca encolhiam), então toda a pressão de "não cabe" caía inteira na grade efêmera —
     * ela era a ÚNICA coisa flexível sem piso, ficando praticamente intocável com 2+ barras (pergunta do
     * Yan, 2026-07-07: "por que a barra renderiza perfeita e a grade não?"). `BarView.onMeasure` usa
     * ESTA MESMA função pra medir de verdade — nunca ajuste um sem o outro.
     */
    fun barHeightPx(metrics: DisplayMetrics, usableHeightPx: Float, scale: Float, barCount: Int): Float {
        val budgetScale = landscapeDampedScale(metrics, scale)
        val idealPx = BAR_IDEAL_HEIGHT_DP * metrics.density * budgetScale
        val maxBudgetPx = usableHeightPx * barHeightFraction(metrics) * budgetScale
        val shrunkPx = maxOf(maxBudgetPx / barCount.coerceAtLeast(1), BAR_MIN_HEIGHT_DP * metrics.density * budgetScale)
        return minOf(idealPx, shrunkPx)
    }

    // Espelha CandidatesView.HEIGHT_DP — nunca ajuste um sem o outro.
    private const val BAR_IDEAL_HEIGHT_DP = 28f

    // Piso: ainda tocável (o alvo de toque de verdade é a LARGURA do botão, via MIN_UNIT_DP — a altura só
    // precisa caber o texto/glifo, 15dp, com folga).
    private const val BAR_MIN_HEIGHT_DP = 22f

    /** Só limita em PAISAGEM (retrato nunca é o gargalo — a tela sobra). */
    private fun barHeightFraction(metrics: DisplayMetrics): Float =
        if (metrics.widthPixels > metrics.heightPixels) BAR_HEIGHT_FRACTION_LANDSCAPE else 1f

    private const val BAR_HEIGHT_FRACTION_LANDSCAPE = 0.14f

    /**
     * Teto de segurança da reserva pro resto do teclado — nunca mais que isso da tela, não importa o
     * slider "Tamanho do teclado". Alto de propósito (raramente deve ser o limitante; quem controla o
     * equilíbrio grade↔teclado no dia a dia é [keyboardHeightFraction]) — só existe pra nunca zerar a
     * grade em casos extremos (achado do Yan, 2026-07-07: sem teto nenhum, o scale máximo colapsava a
     * grade numa linha inutilizável).
     */
    private const val MAX_RESERVE_FRACTION = 0.75f

    /**
     * Reserva pro RESTO do teclado (barra + grade QWERTY quando expandido) pra somar ao orçamento de
     * altura da grade efêmera — sem isto, a grade cabia sozinha na tela mas, somada ao resto do teclado
     * que empilha no mesmo overscan da janela do IME, ainda estourava em paisagem (achado do Yan, modo
     * expandido, 2026-07-06). Usa a MESMA fração de [keyboardHeightFraction] que
     * `KeyboardGridView.rowHeightPx` já aplica (mais baixa em paisagem, de propósito — pedido do Yan,
     * 2026-07-07) — nunca ajuste um sem o outro, senão as duas contas saem de sincronia de novo.
     */
    fun keyboardContentReservePx(
        metrics: DisplayMetrics,
        usableHeightPx: Float,
        scale: Float,
        qwertyRowCount: Int,
        barCount: Int,
    ): Float {
        // Escala amortecida em paisagem (mesma de `KeyboardGridView.rowHeightPx` — pedido do Yan,
        // 2026-07-07: "como se o slide aumentasse menos quando no horizontal"), aplicada no termo por
        // FRAÇÃO e também no PISO — deixar o piso na escala cheia fazia ele vencer o `maxOf` sozinho em
        // 130% e cancelar todo o efeito do amortecimento ("não parece ter feito efeito", achado do Yan,
        // mesma data). O texto (tamanho fixo, 15dp × escala CHEIA) segue cabendo com folga dentro do piso
        // amortecido — MIN_ROW_HEIGHT_DP tem margem de sobra pra isso.
        val budgetScale = landscapeDampedScale(metrics, scale)
        val fractionBasedQwertyPx = usableHeightPx * keyboardHeightFraction(metrics) * budgetScale
        // `KeyboardGridView.rowHeightPx` nunca encolhe abaixo de um piso (a letra vaza da tecla antes
        // disso) — com fileiras suficientes esse piso pode exceder a estimativa por fração; sem o
        // `maxOf`, a reserva voltava a subestimar o QWERTY de verdade (mesma classe de bug do slack,
        // achado do Yan, 2026-07-07). Usa [qwertyRowCount] REAL (4 ou 5, fileira de números) — um "pior
        // caso" fixo (sempre 5) reservava demais pra layouts de 4, zerando a grade sem necessidade
        // (achado do Yan, mesma data: "a grade não funciona de jeito nenhum" no scale máximo).
        val floorBasedQwertyPx = qwertyRowCount * MIN_ROW_HEIGHT_DP * metrics.density * budgetScale
        val qwertyPx = maxOf(fractionBasedQwertyPx, floorBasedQwertyPx)
        // Reserva da(s) BARRA(S): usa a MESMA função de encolhimento que `BarView.onMeasure` de verdade
        // aplica ([barHeightPx]) — as barras agora TAMBÉM encolhem com 2+ empilhadas, então a reserva
        // precisa refletir isso, senão volta a super ou subestimar (achado do Yan, 2026-07-07).
        val barsPx = barHeightPx(metrics, usableHeightPx, scale, barCount) * barCount.coerceAtLeast(1)
        val estimatePx = qwertyPx + barsPx
        return minOf(estimatePx, usableHeightPx * MAX_RESERVE_FRACTION)
    }

    // Espelha KeyboardGridView.MIN_ROW_HEIGHT_DP — nunca ajuste um sem o outro.
    private const val MIN_ROW_HEIGHT_DP = 34f

    /**
     * Folga extra do overscan além de linhas×altura+preview (margem de segurança contra arredondamento) —
     * PRECISA entrar no orçamento de [fittedCellPx] via [reservedHeightPx] em vez de ser somada por fora
     * depois, senão ela escapa da conta de "cabe na tela" (achado do Yan, 2026-07-07: ~80px de estouro
     * não contabilizado no scale máximo vinha exatamente daqui).
     */
    fun overscanSlackPx(metrics: DisplayMetrics, scale: Float): Float =
        OVERSCAN_SLACK_DP * metrics.density * landscapeDampedScale(metrics, scale)

    /**
     * Faixa do preview do dedo ([GridOverlayPopup.PREVIEW_SPACE_DP]) também amortecida em paisagem —
     * mesma classe de bug do slack/reserva da barra: na escala crua, continuava comendo o orçamento da
     * grade com o slider acima de 100% mesmo com o teclado já congelado (achado do Yan, 2026-07-07).
     */
    fun previewSpacePx(metrics: DisplayMetrics, scale: Float): Float =
        GridOverlayPopup.PREVIEW_SPACE_DP * metrics.density * landscapeDampedScale(metrics, scale)

    private const val OVERSCAN_SLACK_DP = 24f

    /**
     * [idealCellWidthPx]/[idealCellHeightPx] até o limite da tela; encolhe só o eixo que vazaria.
     * [reservedHeightPx] é TUDO que já come do orçamento de altura (preview do dedo +
     * [keyboardContentReservePx]) — o que sobra da tela É o orçamento, sem fração extra por cima.
     */
    fun fittedCellPx(
        rows: Int,
        cols: Int,
        idealCellWidthPx: Float,
        idealCellHeightPx: Float,
        screenWidthPx: Float,
        screenHeightPx: Float,
        reservedHeightPx: Float,
    ): Pair<Float, Float> {
        val maxHeightBudgetPx = (screenHeightPx - reservedHeightPx).coerceAtLeast(0f)
        val cellWidthPx = minOf(idealCellWidthPx, screenWidthPx / cols)
        val cellHeightPx = minOf(idealCellHeightPx, maxHeightBudgetPx / rows)
        return cellWidthPx to cellHeightPx
    }
}
