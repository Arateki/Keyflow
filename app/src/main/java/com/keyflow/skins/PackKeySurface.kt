package com.keyflow.skins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import com.keyflow.ime.KeyRole
import com.keyflow.ime.KeySurface
import com.keyflow.ime.SpriteCache
import kotlin.math.roundToInt

/**
 * As teclas de um pacote de skin (SKP-KBD-01): a face de cada tecla é o SVG do pacote — `keyboard_key_*` para as letras,
 * `keyboard_modifier_*` para as de função e os botões das barras — desenhada **uma vez** num bitmap do tamanho da tecla
 * ([SpriteCache], a mesma estrutura do vidro, do Glitch e do Rose Gold) e depois só copiada. Em regime, [draw] é um `drawBitmap`: nada
 * de SVG por quadro (SKP-KBD-04). Com `keyboard.slice` declarado, o sprite é fatiado em 3×3 e os cantos mantêm a proporção.
 *
 * A cor das teclas vem **do SVG** (D3 do design): o app não tinge as faces. A única exceção é o **acento** — quando a view passa uma
 * tinta diferente da comum (shift ativo, mic gravando), o estado precisa aparecer, então a face sai tingida por cima, mantendo a
 * silhueta (`SRC_ATOP`). A face pressionada é a `*_pressed.svg` ou, se o pacote não tem, a normal escurecida em 20%, como nas skins lisas.
 */
class PackKeySurface(private val keyboard: PackKeyboard) : KeySurface {

    // Letras e funções, normal e pressionada, e os acentos (mic, shift): um teclado real usa ~20 tamanhos×estados; 96 não despeja.
    private val sprites = SpriteCache(maxEntries = MAX_SPRITES)
    private val blit = Paint()

    /** Quantos sprites estão no cache agora (só os testes olham). */
    internal val spriteCount: Int get() = sprites.size

    /** Quantas faces foram rasterizadas desde que a superfície nasceu: em regime (tudo em cache) este número não sobe. */
    internal var renderCount = 0
        private set

    /** Sem papel (quem só conhece o contrato antigo): é uma tecla de função. */
    override fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean) =
        draw(canvas, bounds, radius, tint, pressed, KeyRole.MODIFIER)

    override fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean, role: KeyRole) {
        val width = bounds.width().roundToInt()
        val height = bounds.height().roundToInt()
        if (width <= 0 || height <= 0) return
        val accent = if (tint == keyboard.plainTint) 0 else tint
        val key = SpriteCache.Key(width, height, radius = 0, tint = accent, pressed = pressed, variant = role.ordinal + 1)
        val sprite = sprites.get(key, pad = 0) { render(it, role, pressed, accent) }
        // Inteiros: o sprite tem o tamanho arredondado e sai pixel por pixel, sem reamostrar.
        canvas.drawBitmap(sprite, bounds.left.roundToInt().toFloat(), bounds.top.roundToInt().toFloat(), blit)
    }

    private fun render(bitmap: Bitmap, role: KeyRole, pressed: Boolean, accent: Int) {
        renderCount++
        val face = keyboard.faceFor(role, pressed)
        val image = keyboard.image(face.part) ?: return
        val canvas = Canvas(bitmap)
        try {
            val slice = keyboard.slice
            if (slice != null) {
                SvgRender.drawNineSlice(canvas, image, RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), slice)
            } else {
                SvgRender.draw(canvas, image, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            }
        } catch (_: Exception) {
            // Um SVG que passou no import mas não desenha agora: a tecla fica sem face (transparente), o IME segue de pé.
            return
        }
        if (face.darken) canvas.drawColor(PRESSED_SHADE, PorterDuff.Mode.SRC_ATOP)
        if (accent != 0) canvas.drawColor((accent and 0x00FFFFFF) or ACCENT_ALPHA, PorterDuff.Mode.SRC_ATOP)
    }

    private companion object {
        const val MAX_SPRITES = 96

        /** O mesmo escurecimento (20% de preto) que a tecla lisa pressionada leva (`pressedOverlay` das views). */
        const val PRESSED_SHADE = 0x33000000

        /** Quanto o acento cobre a face: forte o bastante para ler o estado, sem apagar o relevo da arte. */
        const val ACCENT_ALPHA = 0xCC000000.toInt()
    }
}
