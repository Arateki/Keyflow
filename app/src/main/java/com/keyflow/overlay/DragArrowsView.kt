package com.keyflow.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Estado das setas do modo-arrasto da orbe: centro da orbe (nas coords da janela), distância de ativação
 * e opacidades por direção. As setas começam opacas e somem conforme o dedo se aproxima da ação.
 */
data class DragArrowsState(
    val centerXScreen: Int, // centro da orbe em coords FÍSICAS de tela (getLocationOnScreen da orbe)
    val centerYScreen: Int,
    val activationPx: Float,
    val upAlpha: Float,
    val lateralAlpha: Float,
    val downAlpha: Float, // ↓ = abrir config
    val lateralLeft: Boolean, // ← (destro) ou → (canhoto), sempre para o centro
)

/**
 * Sobreposição NÃO-tocável, FULL-SCREEN, que desenha as setas (↑ lateral ↓) **relativas ao centro da
 * orbe**. Janela cheia → o sistema não recoloca, então a ↑/↓ ficam sempre no eixo da orbe; o que sai da
 * tela (a lateral apontando pra borda) é só recortado.
 */
class DragArrowsView(context: Context) : View(context) {
    private var cx = 0f
    private var cy = 0f
    private var upAlpha = 1f
    private var lateralAlpha = 1f
    private var downAlpha = 1f
    private var lateralLeft = true
    private var activationPx = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = com.keyflow.ui.theme.OrbAccentArgb // cor da orbe (token único; acompanha a skin)
    }

    fun setArrows(state: DragArrowsState) {
        cx = state.centerXScreen.toFloat()
        cy = state.centerYScreen.toFloat()
        activationPx = state.activationPx
        upAlpha = state.upAlpha
        lateralAlpha = state.lateralAlpha
        downAlpha = state.downAlpha
        lateralLeft = state.lateralLeft
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (activationPx <= 0f) return
        // cx/cy chegam em coords FÍSICAS de tela (getLocationOnScreen da orbe), mas o canvas desta janela
        // tem origem em getLocationOnScreen DELA mesma — que NÃO é (0,0): o sistema insere a status bar
        // (e cutout/nav bar em paisagem). Converto físico→local subtraindo o offset da própria view; isso
        // cancela qualquer inset e alinha as setas à orbe em qualquer canto/orientação. O que sair da tela
        // é só recortado (objetivo do Yan).
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        val lcx = cx - loc[0]
        val lcy = cy - loc[1]
        paint.strokeWidth = activationPx * 0.10f
        val arm = activationPx * 0.26f

        if (upAlpha > 0.01f) { // ↑
            paint.alpha = alpha255(upAlpha)
            val ay = lcy - activationPx
            canvas.drawLine(lcx - arm, ay + arm, lcx, ay, paint)
            canvas.drawLine(lcx + arm, ay + arm, lcx, ay, paint)
        }
        if (downAlpha > 0.01f) { // ↓ (config)
            paint.alpha = alpha255(downAlpha)
            val ay = lcy + activationPx
            canvas.drawLine(lcx - arm, ay - arm, lcx, ay, paint)
            canvas.drawLine(lcx + arm, ay - arm, lcx, ay, paint)
        }
        if (lateralAlpha > 0.01f) { // ← / →
            paint.alpha = alpha255(lateralAlpha)
            if (lateralLeft) {
                val ax = lcx - activationPx
                canvas.drawLine(ax + arm, lcy - arm, ax, lcy, paint)
                canvas.drawLine(ax + arm, lcy + arm, ax, lcy, paint)
            } else {
                val ax = lcx + activationPx
                canvas.drawLine(ax - arm, lcy - arm, ax, lcy, paint)
                canvas.drawLine(ax - arm, lcy + arm, ax, lcy, paint)
            }
        }
    }

    private fun alpha255(alpha: Float) = (alpha.coerceIn(0f, 1f) * 255).toInt()
}
