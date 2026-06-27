package com.keyflow.ui.config

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser

/**
 * As 5 abas da configuração (CFG-01). Os ícones reproduzem os paths SVG (viewBox 24×24) dos mocks
 * `Config Abas` via [PathParser], desenhados deterministicamente (sem dep de material-icons-extended).
 */
enum class ConfigTab(val label: String, private val strokePaths: List<String>, private val fillPaths: List<String>) {
    MODO(
        "Modo",
        listOf("M2 6 h20 a6 6 0 0 1 0 12 H2 a6 6 0 0 1 0 -12 z", "M16 12 m-2.5 0 a2.5 2.5 0 1 0 5 0 a2.5 2.5 0 1 0 -5 0"),
        emptyList(),
    ),
    VOZ(
        "Voz",
        listOf("M3.5 10c3 3 13.5 3 17 0", "M3.5 14c3-3 13.5-3 17 0"),
        emptyList(),
    ),
    TECLADO(
        "Teclado",
        listOf("M2 6 h20 a2 2 0 0 1 2 2 v8 a2 2 0 0 1 -2 2 H2 a2 2 0 0 1 -2 -2 v-8 a2 2 0 0 1 2 -2 z",
            "M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"),
        emptyList(),
    ),
    OVERLAY(
        "Overlay",
        listOf("M3 4 h18 a2 2 0 0 1 2 2 v12 a2 2 0 0 1 -2 2 H3 a2 2 0 0 1 -2 -2 V6 a2 2 0 0 1 2 -2 z"),
        listOf("M16.5 15.5 m-2.6 0 a2.6 2.6 0 1 0 5.2 0 a2.6 2.6 0 1 0 -5.2 0"),
    ),
    DOCS(
        "Docs",
        listOf("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M9 13h6M9 17h6"),
        emptyList(),
    );

    @Composable
    fun Icon(tint: Color, modifier: Modifier = Modifier) {
        Canvas(modifier) {
            val s = size.minDimension / 24f
            translate((size.width - 24f * s) / 2f, (size.height - 24f * s) / 2f) {
                scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                    strokePaths.forEach { d ->
                        drawPath(PathParser().parsePathString(d).toPath(), tint, style = Stroke(width = 1.7f))
                    }
                    fillPaths.forEach { d ->
                        drawPath(PathParser().parsePathString(d).toPath(), tint)
                    }
                }
            }
        }
    }
}
