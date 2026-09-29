package com.keyflow.ime

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Tecla do tema Rose Gold (Yan, 2026-09-29: "um tema feminino baseado em Rose Gold"): cetim rosé metálico, opaco. A face
 * é um degradê da cor da tecla ([tint]: blush nas letras, rosé mais fundo nas de função) — clara em cima, mais quente
 * embaixo —, com um brilho de cetim (um oval suave, não a faixa dura do vidro), um calor rosado na base, o **bisel**
 * metálico (um fio de champanhe em cima que escurece até um rosé fundo embaixo, como uma borda polida) e dois clarões
 * pontuais. Sombra macia rosé-amarronzada por fora.
 *
 * Tudo isso é um sprite desenhado uma vez ([SpriteCache]); a tecla só o copia. O brilho que passa por cima de tempos em
 * tempos é do [ShimmerMotion].
 */
class RoseGoldPainter(private val density: Float) : KeySurface {

    private val pad = ceil(SPRITE_PAD_DP * density).toInt()
    private val sprites = SpriteCache()
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean) {
        val key = SpriteCache.Key(bounds.width().roundToInt(), bounds.height().roundToInt(), radius.roundToInt(), tint, pressed)
        val sprite = sprites.get(key, pad) { render(it, key, radius) }
        canvas.drawBitmap(sprite, bounds.left - pad, bounds.top - pad, blit)
    }

    private fun render(bitmap: Bitmap, key: SpriteCache.Key, radius: Float) {
        val canvas = Canvas(bitmap)
        val d = density
        val face = RectF(pad.toFloat(), pad.toFloat(), (pad + key.width).toFloat(), (pad + key.height).toFloat())
        val r = radius
        val w = face.width()
        val h = face.height()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val shape = Path().apply { addRoundRect(face, r, r, Path.Direction.CW) }

        // 1) Sombra macia rosé-amarronzada por fora.
        p.color = 0x59A45B58
        p.maskFilter = BlurMaskFilter(2.4f * d, BlurMaskFilter.Blur.NORMAL)
        rect.set(face)
        rect.offset(0f, 1.5f * d)
        canvas.drawRoundRect(rect, r, r, p)
        p.maskFilter = null
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        canvas.drawRoundRect(face, r, r, p)
        p.xfermode = null

        canvas.save()
        canvas.clipPath(shape)

        // 2) A face: cetim, claro em cima e mais quente embaixo. Pressionada, escurece e ganha calor.
        val base = if (key.pressed) mix(key.tint, 0xFFD59B93.toInt(), 0.45f) else key.tint
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, face.top, 0f, face.bottom,
            intArrayOf(mix(base, WHITE, 0.55f), base, mix(base, 0xFFC98B85.toInt(), 0.38f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(face, p)
        p.shader = null

        // 3) O calor rosado da base (um degradê radial achatado) e o brilho de cetim em cima (um oval suave).
        val cx = face.centerX()
        val cy = face.bottom - 0.04f * h
        val glowRadius = 0.7f * w
        canvas.save()
        canvas.scale(1f, 0.45f, cx, cy)
        p.shader = RadialGradient(cx, cy, glowRadius, 0x66E6A8A0, 0x00E6A8A0, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, glowRadius, p)
        p.shader = null
        canvas.restore()
        rect.set(face.left - 0.12f * w, face.top - 0.60f * h, face.right + 0.12f * w, face.top + 0.44f * h)
        p.shader = LinearGradient(0f, face.top, 0f, face.top + 0.44f * h, 0x8CFFFFFF.toInt(), 0x0AFFFFFF, Shader.TileMode.CLAMP)
        canvas.drawOval(rect, p)
        p.shader = null
        canvas.restore()

        // 4) O bisel metálico: um fio de champanhe em cima que escurece até um rosé fundo embaixo, e um fio escuro por fora.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.shader = LinearGradient(0f, face.top, 0f, face.bottom, 0xFFFFF7EE.toInt(), 0xCCB97A75.toInt(), Shader.TileMode.CLAMP)
        rect.set(face)
        rect.inset(0.75f * d, 0.75f * d)
        canvas.drawRoundRect(rect, r - 0.75f * d, r - 0.75f * d, p)
        p.shader = null
        p.strokeWidth = d
        p.color = 0x8C9E5F5B.toInt()
        rect.set(face)
        rect.inset(0.5f * d, 0.5f * d)
        canvas.drawRoundRect(rect, r - 0.5f * d, r - 0.5f * d, p)
        // O fio interno do bisel, mais claro: a espessura do metal.
        p.strokeWidth = 0.8f * d
        p.color = 0x59FFFFFF
        rect.set(face)
        rect.inset(2.2f * d, 2.2f * d)
        canvas.drawRoundRect(rect, r - 2.2f * d, r - 2.2f * d, p)

        // 5) Os clarões pontuais: um no canto de cima à esquerda e um menor no de baixo à direita.
        p.style = Paint.Style.FILL
        p.color = 0xE6FFFFFF.toInt()
        canvas.drawCircle(face.left + 0.13f * w, face.top + 0.14f * h, maxOf(1.1f * d, 0.028f * h), p)
        p.color = 0x8CFFFFFF.toInt()
        canvas.drawCircle(face.right - 0.14f * w, face.bottom - 0.18f * h, maxOf(0.9f * d, 0.02f * h), p)
    }

    /** A mistura de [a] com [b] por [t] (0 = a, 1 = b), em cada canal, alfa incluído. */
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int = (((a shr shift) and 0xFF) * (1f - t) + ((b shr shift) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private companion object {
        const val SPRITE_PAD_DP = 5f
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
