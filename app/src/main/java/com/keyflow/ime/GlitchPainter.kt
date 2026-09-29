package com.keyflow.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Tecla do tema Glitch (o efeito de falha gráfica da logo do TikTok), na versão **discreta**: o Yan achou as teclas
 * azuis e as bordas brancas "estourando, como um tema de alto contraste" e pediu as cores do efeito só em detalhes
 * e nas falhas. Então a face é um cinza escuro neutro, com uma borda clara fraca; a cor mora em:
 * - **duas marcas de registro** — um traço ciano no canto de cima à esquerda e um vermelho no de baixo à direita;
 * - **o rótulo em três camadas** (ciano, vermelho e a tinta), com um deslocamento mínimo em repouso;
 * - **a falha em si** ([setEffect], dirigido por [KeyboardMotion]): durante uma rajada o deslocamento do rótulo
 *   cresce, as bordas ganham franjas ciano e vermelha e algumas teclas levam uma rasgadura de cor.
 *
 * A face (com as marcas) é um sprite desenhado uma vez ([SpriteCache]); só o efeito é desenhado a cada quadro, e só
 * enquanto há falha.
 */
class GlitchPainter(private val density: Float) : KeySurface {

    private val sprites = SpriteCache()
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slicePaint = Paint()
    private val rect = RectF()

    private var effect = 0f

    override fun setEffect(amount: Float) {
        effect = amount.coerceIn(0f, 1f)
    }

    override fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean) {
        val key = SpriteCache.Key(bounds.width().roundToInt(), bounds.height().roundToInt(), radius.roundToInt(), tint, pressed)
        val sprite = sprites.get(key, 0) { render(it, key, radius) }
        canvas.drawBitmap(sprite, bounds.left, bounds.top, blit)
        if (effect > EFFECT_MIN) drawGlitch(canvas, bounds, radius)
    }

    /** A face de uma tecla: cinza escuro, borda fraca e as duas marcas de registro. */
    private fun render(bitmap: Bitmap, key: SpriteCache.Key, radius: Float) {
        val canvas = Canvas(bitmap)
        val d = density
        val face = RectF(0f, 0f, key.width.toFloat(), key.height.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Pressionada, a face clareia (não inverte para branco: era o "alto contraste").
        p.color = if (key.pressed) lighten(key.tint) else key.tint
        canvas.drawRoundRect(face, radius, radius, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = d
        p.color = if (key.pressed) 0x66FFFFFF else 0x2EFFFFFF
        rect.set(face)
        rect.inset(d / 2f, d / 2f)
        canvas.drawRoundRect(rect, radius - d / 2f, radius - d / 2f, p)

        // As marcas de registro: o traço ciano em cima à esquerda, o vermelho embaixo à direita (maiores ao apertar).
        val tick = (if (key.pressed) 9f else 5.5f) * d
        p.strokeWidth = 1.4f * d
        p.strokeCap = Paint.Cap.ROUND
        p.color = CYAN_TICK
        val inset = radius * 0.55f + d
        canvas.drawLine(inset, 0.9f * d, inset + tick, 0.9f * d, p)
        p.color = RED_TICK
        canvas.drawLine(face.right - inset - tick, face.bottom - 0.9f * d, face.right - inset, face.bottom - 0.9f * d, p)
    }

    /** A falha: franjas ciano e vermelha nas bordas e, em algumas teclas, uma rasgadura de cor. Só com efeito > 0. */
    private fun drawGlitch(canvas: Canvas, bounds: RectF, radius: Float) {
        val d = density
        val e = effect
        val split = (0.8f + 1.8f * e) * d
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = d
        rect.set(bounds)
        rect.inset(d / 2f, d / 2f)
        paint.color = withAlpha(CYAN, 0.42f * e)
        canvas.save()
        canvas.translate(-split, 0f)
        canvas.drawRoundRect(rect, radius, radius, paint)
        canvas.restore()
        paint.color = withAlpha(RED, 0.42f * e)
        canvas.save()
        canvas.translate(split, 0f)
        canvas.drawRoundRect(rect, radius, radius, paint)
        canvas.restore()

        val h = hash(bounds)
        if (h % SLICE_ONE_IN == 0) {
            val y = bounds.top + bounds.height() * (0.2f + 0.55f * ((h ushr 3) % 100) / 100f)
            val thick = d * (1.1f + ((h ushr 9) % 3) * 0.5f)
            slicePaint.color = withAlpha(if ((h ushr 2) % 2 == 0) CYAN else RED, 0.7f * e)
            canvas.drawRect(bounds.left - split, y, bounds.right + split * 1.5f, y + thick, slicePaint)
        }
    }

    override val labelPasses: Int get() = 3

    override fun labelOffsetX(pass: Int): Float {
        val offset = (0.6f + 1.7f * effect) * density
        return when (pass) {
            0 -> -offset
            1 -> offset
            else -> 0f
        }
    }

    override fun labelColor(pass: Int, ink: Int, pressed: Boolean): Int = when (pass) {
        0 -> withAlpha(CYAN, if (pressed) 0.9f else 0.5f + 0.4f * effect)
        1 -> withAlpha(RED, if (pressed) 0.9f else 0.5f + 0.4f * effect)
        else -> ink
    }

    /** Número estável a partir da posição da tecla: a mesma tecla leva sempre a mesma rasgadura. */
    private fun hash(bounds: RectF): Int =
        ((bounds.left * 31f + bounds.top * 17f).toInt() * -0x61c88647) ushr 8 and 0x7FFFFFFF

    private fun lighten(color: Int): Int {
        fun ch(shift: Int) = (((color shr shift) and 0xFF) + 0x14).coerceAtMost(255)
        return (color and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun withAlpha(color: Int, alpha: Float): Int = (((alpha.coerceIn(0f, 1f) * 255f).roundToInt()) shl 24) or (color and 0x00FFFFFF)

    companion object {
        const val CYAN = 0x25F4EE
        const val RED = 0xFE2C55
        private const val CYAN_TICK = 0xD925F4EE.toInt()
        private const val RED_TICK = 0xD9FE2C55.toInt()
        private const val EFFECT_MIN = 0.02f
        private const val SLICE_ONE_IN = 3
    }
}
