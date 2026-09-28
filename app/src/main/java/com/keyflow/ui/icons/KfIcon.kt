package com.keyflow.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Desenha um [KfIconSpec] no Compose, centralizado e escalado ao menor lado do [modifier]. Os paths são
 * interpretados uma vez por ícone (não a cada quadro). [strokeWidth] sobrepõe a espessura do ícone, em
 * unidades do grid 24.
 */
@Composable
fun KfIcon(icon: KfIconSpec, tint: Color, modifier: Modifier, strokeWidth: Float = icon.strokeWidth) {
    val strokes = remember(icon) { icon.strokes.map { PathParser().parsePathString(it).toPath() } }
    val fills = remember(icon) { icon.fills.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        translate((size.width - 24f * s) / 2f, (size.height - 24f * s) / 2f) {
            scale(s, s, pivot = Offset.Zero) {
                val style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                strokes.forEach { drawPath(it, tint, style = style) }
                fills.forEach { drawPath(it, tint) }
            }
        }
    }
}
