package com.keyflow.notificacao.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.keyflow.notificacao.AparenciaDaOrbe
import com.keyflow.notificacao.GlifoDaOrbe
import com.keyflow.skins.PackOrb
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.centerInkOf
import com.keyflow.ui.orb.drawCancelGlyph
import com.keyflow.ui.orb.drawErrorGlyph
import com.keyflow.ui.orb.drawEyeGlyph
import com.keyflow.ui.orb.drawOrb
import com.keyflow.ui.orb.drawPauseGlyph
import com.keyflow.ui.orb.toPackState

/**
 * Desenha a orbe da notificação num `Bitmap` ARGB transparente (NTF-25, NTF-29). Reaproveita os desenhos da orbe flutuante
 * (`drawOrb`, `drawMascotOrb`, a skin de pacote) e os glifos de `ui/orb`, com **tempo fixo e pose fixa** por estado: a notificação não anima,
 * e duas chamadas iguais dão o mesmo pixel (testável e cacheável).
 */
object RenderizadorDeOrbe {
    /** O instante do desenho: fixo, para o mesmo estado dar sempre a mesma pose. */
    const val TEMPO_FIXO_MS = 1500f

    fun estadoDaOrbe(a: AparenciaDaOrbe): OrbState = when (a) {
        AparenciaDaOrbe.IDLE -> OrbState.Idle
        AparenciaDaOrbe.LISTENING -> OrbState.Listening
        AparenciaDaOrbe.PAUSED -> OrbState.Paused
        AparenciaDaOrbe.TRANSCRIBING -> OrbState.Transcribing
    }

    /**
     * @param glifo o glifo do centro (pausa, lixeira, X, olho), ou `null`; vem acima do centro quando há tempo miúdo, mas o tempo é do
     * `Chronometer` da `RemoteViews`, não do bitmap.
     * @param pack a orbe do pacote quando [style] é [OrbStyle.PACK]; sem ela (ou sem o frame) o estilo cai no Ateliê.
     */
    fun renderizar(
        style: OrbStyle,
        aparencia: AparenciaDaOrbe,
        ladoPx: Int,
        glifo: GlifoDaOrbe? = null,
        pack: PackOrb? = null,
    ): Bitmap {
        require(ladoPx > 0) { "lado inválido: $ladoPx" }
        val estado = estadoDaOrbe(aparencia)
        if (style == OrbStyle.PACK) {
            val quadro = pack?.frameFor(estado.toPackState(), TEMPO_FIXO_MS, ladoPx, deterministic = true)
            if (quadro != null) {
                val saida = Bitmap.createBitmap(ladoPx, ladoPx, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(saida).drawBitmap(quadro, 0f, 0f, null)
                desenharGlifo(saida, glifo, centerInkOf(style, pack.centerInk))
                return saida
            }
        }
        val estilo = if (style == OrbStyle.PACK) OrbStyle.ATELIE else style
        val imagem: ImageBitmap = ImageBitmap(ladoPx, ladoPx)
        val lado = ladoPx.toFloat()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(imagem), Size(lado, lado)) {
            drawOrb(TEMPO_FIXO_MS, estado, estilo, hasCenter = glifo != null)
        }
        val copia = imagem.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        desenharGlifo(copia, glifo, centerInkOf(estilo))
        return copia
    }

    /** Desenha o [glifo] no centro de um bitmap já pronto (a skin Sistema) na [tinta] ARGB. */
    fun glifoSobre(bitmap: Bitmap, glifo: GlifoDaOrbe, tinta: Int) = desenharGlifo(bitmap, glifo, Color(tinta))

    private fun desenharGlifo(bitmap: Bitmap, glifo: GlifoDaOrbe?, tinta: Color) {
        if (glifo == null) return
        val g = (bitmap.width * 0.5f).toInt().coerceAtLeast(1)
        val imagem = ImageBitmap(g, g)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(imagem), Size(g.toFloat(), g.toFloat())) {
            when (glifo) {
                GlifoDaOrbe.PAUSA -> drawPauseGlyph(tinta)
                GlifoDaOrbe.LIXEIRA -> drawCancelGlyph()
                GlifoDaOrbe.X -> drawErrorGlyph()
                GlifoDaOrbe.OLHO -> drawEyeGlyph(tinta)
            }
        }
        val deslocamento = (bitmap.width - g) / 2f
        android.graphics.Canvas(bitmap).drawBitmap(imagem.asAndroidBitmap(), deslocamento, deslocamento, null)
    }
}
