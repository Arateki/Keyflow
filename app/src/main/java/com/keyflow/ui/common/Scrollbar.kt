package com.keyflow.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Indicador de rolagem sutil: desenha um "thumb" fino à direita quando há conteúdo além da viewport
 * ([ScrollState.maxValue] > 0), proporcional à posição. Some sozinho quando tudo cabe na tela.
 */
fun Modifier.verticalScrollbar(
    state: ScrollState,
    color: Color,
    width: Dp = 2.5.dp,
    minThumb: Dp = 28.dp,
    inset: Dp = 3.dp,
    // Quanto a barra "sangra" para fora da borda direita do nó (compensa o padding lateral do painel,
    // levando a barra ao canto da tela em vez de sobre o conteúdo).
    rightBleed: Dp = 0.dp,
): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max <= 0) return@drawWithContent

    val pad = inset.toPx()
    val track = size.height - pad * 2
    val total = track + max
    val thumbH = (track * track / total).coerceAtLeast(minThumb.toPx())
    val travel = track - thumbH
    val y = pad + travel * (state.value.toFloat() / max)
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - w + rightBleed.toPx(), y),
        size = Size(w, thumbH),
        cornerRadius = CornerRadius(w / 2f, w / 2f),
    )
}
