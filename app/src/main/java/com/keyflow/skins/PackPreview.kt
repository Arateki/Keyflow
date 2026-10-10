package com.keyflow.skins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import kotlin.math.roundToInt

/**
 * As miniaturas dos cartões das skins instaladas (SKP-UI-01): o desenho **de verdade** do pacote, renderizado do SVG — o frame 1 de `idle`
 * da orbe, e a bandeja com uma face de tecla do teclado. Cada função faz IO e renderiza: chame fora da thread principal.
 *
 * Nunca lançam: pacote que sumiu, SVG que não lê ou que a biblioteca não desenha é `null`, e o cartão fica sem miniatura em vez de derrubar a tela.
 */
object PackPreview {
    private const val TAG = "SKP"

    /**
     * O primeiro frame de `idle` num quadrado de [sidePx] (a orbe inteira, com halo, centralizada sem esticar), ou `null` se o pacote não tem
     * orbe ou o frame não desenha.
     */
    fun orb(pack: SkinPack, sidePx: Int): Bitmap? {
        if (!pack.hasOrb || sidePx <= 0) return null
        return try {
            val file = pack.framesOf(OrbPackState.IDLE).firstOrNull() ?: return null
            val image = SvgRender.parse(pack.readSvg(file) ?: return null)
            SvgRender.toBitmap(image, sidePx, sidePx, SvgFit.MEET)
        } catch (e: Exception) {
            Log.w(TAG, "${pack.id}: a miniatura da orbe não desenhou (${e.message})")
            null
        }
    }

    /**
     * A bandeja do teclado (o SVG esticado, ou a cor lisa `keyboard.trayColor`) com a face da tecla normal por cima, com a mesma margem das
     * amostras das skins nativas, em [widthPx]×[heightPx]. A face usa o `keyboard.slice` do manifest, como no teclado de verdade. `null` se o
     * pacote não tem teclado ou a tecla normal não carrega.
     */
    fun keyboard(pack: SkinPack, widthPx: Int, heightPx: Int): Bitmap? {
        if (!pack.hasKeyboard || widthPx <= 0 || heightPx <= 0) return null
        return try {
            val keyboard = PackKeyboard.load(pack) ?: return null
            val face = keyboard.image(KeyboardPart.KEY_NORMAL) ?: return null
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val tray = keyboard.image(KeyboardPart.TRAY)
            if (tray != null) {
                try {
                    SvgRender.draw(canvas, tray, 0f, 0f, widthPx.toFloat(), heightPx.toFloat(), SvgFit.STRETCH)
                } catch (_: Exception) {
                    canvas.drawColor(keyboard.manifest.trayColor)
                }
            } else {
                canvas.drawColor(keyboard.manifest.trayColor)
            }
            // A face em px inteiros (o fatiamento 3×3 pede bordas inteiras): a tecla ocupa o miolo do cartão, como a das amostras nativas.
            val key = RectF(
                (widthPx * KEY_INSET_X).roundToInt().toFloat(),
                (heightPx * KEY_INSET_Y).roundToInt().toFloat(),
                (widthPx * (1f - KEY_INSET_X)).roundToInt().toFloat(),
                (heightPx * (1f - KEY_INSET_Y)).roundToInt().toFloat(),
            )
            val slice = keyboard.slice
            if (slice != null) SvgRender.drawNineSlice(canvas, face, key, slice) else SvgRender.draw(canvas, face, key.left, key.top, key.width(), key.height())
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "${pack.id}: a miniatura do teclado não desenhou (${e.message})")
            null
        }
    }

    /** A folga da tecla dentro da miniatura (as amostras nativas do teclado usam 10 dp × 9 dp num cartão de ~64 × 40). */
    private const val KEY_INSET_X = 0.16f
    private const val KEY_INSET_Y = 0.2f
}
