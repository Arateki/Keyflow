package com.keyflow.ime.bars

import com.keyflow.core.shortcuts.BarButton

/**
 * Geometria pura das barras (BAR-01/05): particiona a largura por **peso por tipo** (como a grade Canvas
 * faz por tecla). Regra do overflow numa conta só: a unidade de largura é
 * `max(larguraDaView / somaDosPesos, unidadeMínima)` — se a mínima vencer, o conteúdo passa da view e a
 * barra rola ([BarLayout.scrollable]).
 */
object BarGeometry {

    data class PlacedButton(val index: Int, val button: BarButton, val left: Float, val width: Float) {
        val right: Float get() = left + width
        fun contains(x: Float): Boolean = x >= left && x < right
    }

    data class BarLayout(
        val buttons: List<PlacedButton>,
        val contentWidth: Float,
        val viewWidth: Float,
    ) {
        val scrollable: Boolean get() = contentWidth > viewWidth
        val maxScroll: Float get() = (contentWidth - viewWidth).coerceAtLeast(0f)
        fun buttonAt(contentX: Float): PlacedButton? = buttons.firstOrNull { it.contains(contentX) }

        companion object {
            val EMPTY = BarLayout(emptyList(), 0f, 0f)
        }
    }

    /** Pesos padrão por tipo (paridade aproximada com as barras atuais; Candidates é o slot flexível). */
    fun weightFor(button: BarButton): Float = when (button) {
        BarButton.Mic -> 1.5f
        BarButton.ConfigBtn -> 0.9f
        BarButton.ToggleExpand -> 1f
        BarButton.Paste -> 1f
        BarButton.Candidates -> 4f
        is BarButton.SingleKey -> 0.8f
        BarButton.Joystick -> 1.2f
        is BarButton.GridTrigger -> 1f
    }

    /**
     * Posiciona [buttons] numa view de [viewWidthPx]. [minUnitPx] é a largura mínima de um botão de peso
     * 1 (alvo de toque); barra que não comporta todo mundo nesse mínimo vira rolável (BAR-05).
     */
    fun layout(buttons: List<BarButton>, viewWidthPx: Float, minUnitPx: Float): BarLayout {
        if (buttons.isEmpty() || viewWidthPx <= 0f) return BarLayout.EMPTY
        val totalWeight = buttons.map(::weightFor).sum()
        val unit = maxOf(viewWidthPx / totalWeight, minUnitPx)
        var x = 0f
        val placed = buttons.mapIndexed { index, button ->
            val width = weightFor(button) * unit
            PlacedButton(index, button, x, width).also { x += width }
        }
        return BarLayout(placed, contentWidth = x, viewWidth = viewWidthPx)
    }

    /**
     * Botões que servem de APOIO para rolar a barra: os de toque simples.
     *
     * Ficam de fora os arrastáveis (mic, alavanca, grade), que capturam o arrasto para os próprios
     * gestos, e a faixa de sugestões, que é uma `View` filha com rolagem interna — o toque nela nunca
     * chega à barra. Uma barra que não cabe e não tem nenhum apoio deixa o excedente inalcançável; é o
     * que o aviso do editor de barras usa para explicar isso ANTES de a pessoa montar (Yan, 2026-09-08).
     */
    fun scrollAnchors(buttons: List<BarButton>): List<BarButton> =
        buttons.filterNot { isDraggable(it) || it == BarButton.Candidates }

    /** Botões de gesto contínuo capturam o toque — a barra não rola a partir deles (BAR-05). */
    fun isDraggable(button: BarButton): Boolean = when (button) {
        BarButton.Joystick, is BarButton.GridTrigger, BarButton.Mic -> true
        else -> false
    }
}
