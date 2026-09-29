package com.keyflow.ime

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
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
 * Tecla de vidro do Aero, usada pela grade e pelas barras, sobre a lâmina translúcida do teclado
 * ([KeyboardBackdrop]) — e, através dela, sobre o que o app desenha atrás.
 *
 * A face da tecla é desenhada **uma vez** num sprite ([SpriteCache]) e copiada a cada quadro; por isso pode ter o nível
 * de detalhe da orbe de vidro, de baixo para cima:
 * - **sombra** macia por fora (o interior é recortado: a tecla é translúcida e uma sombra por baixo daria cinza);
 * - **o véu** da cor da tecla, em degradê;
 * - **a borda de Fresnel**: o vidro é mais denso junto ao aro e mais claro no meio;
 * - **a luz da base**, refratada, e um fio de luz junto à borda de baixo;
 * - **o brilho**, um oval que sai da tecla (então o lado de baixo dele é curvo, como uma gota) e o clarão pontual;
 * - **o aro duplo**: um fio claro por fora e outro mais fraco por dentro, que dão a espessura do vidro;
 * - **o grão** do vidro fosco, só quando o aparelho não desfoca o que está atrás ([blurBehind] falso): ali o véu é
 *   mais denso para o conteúdo de trás não atrapalhar as letras.
 *
 * O reflexo diagonal, único para o teclado inteiro, é o que sobra dinâmico: uma faixa por tecla, com um degradê só
 * deslocado por view.
 */
class GlassPainter(private val density: Float, private val blurBehind: Boolean = true) : KeySurface {

    private val pad = ceil(SPRITE_PAD_DP * density).toInt()
    private val sprites = SpriteCache()
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)

    // O reflexo: um degradê diagonal com paradas duras, do tamanho da lâmina, deslocado como a tecla.
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheenShader: LinearGradient? = null
    private var sheenWidth = 0
    private var sheenHeight = 0
    private val sheenMatrix = Matrix()
    private val rect = RectF()

    // O grão do vidro fosco: um azulejo de ruído repetido (uma vez só, compartilhado por todos os sprites).
    private val grain: BitmapShader? = if (blurBehind) null else noiseShader()

    /**
     * Antes das teclas de uma view: a lâmina atrás dela e onde a origem do canvas está sobre a lâmina
     * ([dx]/[dy], já descontada a rolagem). Sem lâmina (ou antes do 1º tamanho), o vidro fica sem reflexo.
     */
    override fun useSheet(sheet: GlassSheet?, dx: Float, dy: Float) {
        if (sheet == null || sheet.widthPx <= 0 || sheet.heightPx <= 0) {
            sheenShader = null
            return
        }
        if (sheet.widthPx != sheenWidth || sheet.heightPx != sheenHeight || sheenShader == null) {
            sheenWidth = sheet.widthPx
            sheenHeight = sheet.heightPx
            // Um shader por view (não compartilhado): a matriz de cada uma muda a cada quadro.
            sheenShader = GlassSheen.shader(sheenWidth, sheenHeight)
            sheenPaint.shader = sheenShader
        }
        sheenMatrix.setTranslate(-dx, -dy)
        sheenShader?.setLocalMatrix(sheenMatrix)
    }

    override fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean) {
        val key = SpriteCache.Key(bounds.width().roundToInt(), bounds.height().roundToInt(), radius.roundToInt(), tint, pressed)
        val sprite = sprites.get(key, pad) { render(it, key, radius) }
        canvas.drawBitmap(sprite, bounds.left - pad, bounds.top - pad, blit)
        if (sheenShader != null) canvas.drawRoundRect(bounds, radius, radius, sheenPaint)
    }

    /** A face inteira de uma tecla, desenhada uma vez. */
    private fun render(bitmap: Bitmap, key: SpriteCache.Key, radius: Float) {
        val canvas = Canvas(bitmap)
        val d = density
        val face = RectF(pad.toFloat(), pad.toFloat(), (pad + key.width).toFloat(), (pad + key.height).toFloat())
        val r = radius
        val w = face.width()
        val h = face.height()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val shape = Path().apply { addRoundRect(face, r, r, Path.Direction.CW) }

        // 1) Sombra macia por fora; o interior da tecla é recortado (vidro translúcido não tem sombra por baixo).
        p.color = 0x4006264F
        p.maskFilter = BlurMaskFilter(2.4f * d, BlurMaskFilter.Blur.NORMAL)
        rect.set(face)
        rect.offset(0f, 1.5f * d)
        canvas.drawRoundRect(rect, r, r, p)
        p.maskFilter = null
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        canvas.drawRoundRect(face, r, r, p)
        p.xfermode = null

        // 2) O fio escuro por fora: separa a tecla de um fundo claro.
        p.style = Paint.Style.STROKE
        p.strokeWidth = d
        p.color = 0x4D0B3A66
        rect.set(face)
        rect.inset(-0.5f * d, -0.5f * d)
        canvas.drawRoundRect(rect, r + 0.5f * d, r + 0.5f * d, p)

        canvas.save()
        canvas.clipPath(shape)

        // 3) O véu: a cor da tecla, mais leve em cima e mais denso embaixo. Sem desfoque do aparelho ele é mais denso.
        val boost = if (blurBehind) 1f else FROST_BOOST
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, face.top, 0f, face.bottom, scaleAlpha(key.tint, 0.8f * boost), scaleAlpha(key.tint, 1.15f * boost), Shader.TileMode.CLAMP)
        canvas.drawRect(face, p)
        p.shader = null
        if (grain != null) {
            p.shader = grain
            p.alpha = 26
            canvas.drawRect(face, p)
            p.shader = null
            p.alpha = 255
        }

        // 4) A borda de Fresnel: o vidro é mais denso junto ao aro e mais claro no meio.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.4f * d
        p.color = 0x66FFFFFF
        p.maskFilter = BlurMaskFilter(1.8f * d, BlurMaskFilter.Blur.NORMAL)
        rect.set(face)
        rect.inset(1.7f * d, 1.7f * d)
        canvas.drawRoundRect(rect, r - 1.7f * d, r - 1.7f * d, p)
        p.maskFilter = null

        // 5) A luz da base: refratada, num oval achatado (o degradê radial escalado), e um fio junto à borda de baixo.
        p.style = Paint.Style.FILL
        val cx = face.centerX()
        val cy = face.bottom - 0.06f * h
        val glowRadius = 0.62f * w
        canvas.save()
        canvas.scale(1f, 0.5f, cx, cy)
        p.shader = RadialGradient(cx, cy, glowRadius, 0x99CFF5FF.toInt(), 0x00CFF5FF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, glowRadius, p)
        p.shader = null
        canvas.restore()
        canvas.save()
        canvas.clipRect(face.left, face.top + 0.68f * h, face.right, face.bottom)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * d
        p.color = 0x99E6FBFF.toInt()
        rect.set(face)
        rect.inset(2.1f * d, 2.1f * d)
        canvas.drawRoundRect(rect, r - 2.1f * d, r - 2.1f * d, p)
        canvas.restore()

        // 6) O brilho: um oval que passa da tecla dos dois lados, então a borda de baixo dele é curva, como uma gota.
        canvas.save()
        rect.set(face)
        rect.inset(1.3f * d, 1.3f * d)
        canvas.clipPath(Path().apply { addRoundRect(rect, r - 1.3f * d, r - 1.3f * d, Path.Direction.CW) })
        p.style = Paint.Style.FILL
        rect.set(face.left - 0.14f * w, face.top - 0.64f * h, face.right + 0.14f * w, face.top + 0.47f * h)
        p.shader = LinearGradient(0f, face.top, 0f, face.top + 0.47f * h, 0xB8FFFFFF.toInt(), 0x0FFFFFFF, Shader.TileMode.CLAMP)
        canvas.drawOval(rect, p)
        p.shader = null
        canvas.restore()

        // 7) O clarão pontual do canto e um reflexo menor no lado oposto.
        p.color = 0xE6FFFFFF.toInt()
        rect.set(face.left + 0.10f * w, face.top + 0.07f * h, face.left + 0.30f * w, face.top + 0.15f * h)
        canvas.drawOval(rect, p)
        p.color = 0x8CFFFFFF.toInt()
        canvas.drawCircle(face.right - 0.15f * w, face.bottom - 0.20f * h, maxOf(1.2f * d, 0.03f * h), p)

        if (key.pressed) {
            p.color = 0x8018C4FF.toInt()
            canvas.drawRect(face, p)
        }
        canvas.restore()

        // 8) O aro duplo: um fio claro por fora (mais forte em cima) e outro mais fraco por dentro, a espessura do vidro.
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (key.pressed) 1.5f * d else d
        if (key.pressed) {
            p.color = 0xFF8AEBFF.toInt()
        } else {
            p.shader = LinearGradient(0f, face.top, 0f, face.bottom, 0xFFFFFFFF.toInt(), 0x73FFFFFF, Shader.TileMode.CLAMP)
        }
        rect.set(face)
        rect.inset(p.strokeWidth / 2f, p.strokeWidth / 2f)
        canvas.drawRoundRect(rect, r - p.strokeWidth / 2f, r - p.strokeWidth / 2f, p)
        p.shader = null
        p.strokeWidth = 0.8f * d
        p.color = 0x38FFFFFF
        rect.set(face)
        rect.inset(2.3f * d, 2.3f * d)
        canvas.drawRoundRect(rect, r - 2.3f * d, r - 2.3f * d, p)
    }

    /** O grão do vidro fosco: pontos claros e escuros muito fracos, num azulejo de 48px que se repete. */
    private fun noiseShader(): BitmapShader {
        val size = 48
        val pixels = IntArray(size * size)
        var seed = 0x2F6E2B1
        for (i in pixels.indices) {
            seed = seed * 1103515245 + 12345
            val v = (seed ushr 16) and 0xFF
            val light = (seed ushr 8) and 1 == 0
            val alpha = if (v < 60) v else 0
            pixels[i] = (alpha shl 24) or if (light) 0x00FFFFFF else 0x00234466
        }
        val tile = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun scaleAlpha(color: Int, factor: Float): Int {
        val a = ((color ushr 24) * factor).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private companion object {
        /** Folga do sprite em volta da tecla, para a sombra macia caber. */
        const val SPRITE_PAD_DP = 5f

        /** Sem desfoque do aparelho, o véu das teclas é bem mais denso: o que está atrás não pode atrapalhar as letras. */
        const val FROST_BOOST = 1.75f
    }
}
