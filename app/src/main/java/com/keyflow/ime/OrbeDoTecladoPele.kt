package com.keyflow.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.keyflow.skins.PackOrb
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.drawOrb
import com.keyflow.ui.orb.drawPackOrb
import com.keyflow.ui.orb.orbStyleFor
import com.keyflow.ui.orb.toPackState

/** Instante usado quando nada anda (prévia, galeria, golden): o mesmo da orbe estática de sempre. */
internal const val TEMPO_CONGELADO_DA_ORBE_MS = 1234f

/** Qual pele a orbe do teclado veste (KBO-22, KBO-23): o que a pessoa escolheu, ou o Ateliê quando não dá. */
internal object EscolhaDaPeleDaOrbe {
    /**
     * [skinId] guardado (nunca reescrito aqui); sem o direito, a skin paga mostra o Ateliê; pacote sem quadros de orbe
     * ([temQuadrosDeOrbe] falso: sumiu, quebrou ou só tem teclado) também. O pacote em si não é bloqueado.
     */
    fun estilo(skinId: String, skinsUnlocked: Boolean, temQuadrosDeOrbe: Boolean): OrbStyle {
        val estilo = orbStyleFor(skinId, skinsUnlocked)
        return if (estilo == OrbStyle.PACK && !temQuadrosDeOrbe) OrbStyle.ATELIE else estilo
    }
}

/**
 * A pele da orbe num bitmap em cache (KBO-23): o pintor da orbe (`drawOrb`, o mesmo do overlay e da config) desenhado uma vez por
 * quadro distinto, fora do caminho de cada tecla. O cache é uma entrada só: pele, pacote, estado, lado, fundo, miolo e instante — com
 * o instante congelado (ou a pele parada) ela não se refaz; com a pele andando, refaz a cada quadro pedido.
 */
internal class OrbeDoTecladoPele(private val densidade: Float) {
    private val escopo = CanvasDrawScope()
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var chave: Chave? = null

    /** Quantas vezes o pintor rodou — para o teste provar que o quadro repetido não refaz o bitmap. */
    var desenhosParaTeste = 0
        private set

    /** O pacote ainda não entregou o quadro do estado e a pele caiu no Ateliê neste quadro: o teclado deve pedir outro. */
    var aguardandoPacote = false
        private set

    private data class Chave(
        val estilo: OrbStyle,
        val pacote: PackOrb?,
        val versaoDoPacote: Int,
        val estado: OrbState,
        val lado: Int,
        val fundo: Int,
        val miolo: Boolean,
        val tempoMs: Float,
    )

    /**
     * O quadro da orbe com [lado] px. [fundo] é a cor lisa do teclado atrás dela (0 = sem fundo: teclados de vidro), que o vidro e o halo
     * atravessam — por isso o tema claro e o escuro dão bitmaps diferentes mesmo no Ateliê. [miolo]: há tempo ou glifo no centro.
     * [deterministico]: o quadro do pacote é renderizado já, na hora (prévia, golden); falso (teclado real), ele chega do fundo.
     */
    fun quadro(
        estilo: OrbStyle,
        pacote: PackOrb?,
        estado: OrbState,
        lado: Int,
        fundo: Int,
        miolo: Boolean,
        tempoMs: Float,
        deterministico: Boolean = true,
    ): Bitmap {
        val nova = Chave(estilo, pacote, pacote?.version ?: 0, estado, lado, fundo, miolo, tempoMs)
        var atual = bitmap
        if (atual == null || atual.width != lado || atual.height != lado) {
            atual = Bitmap.createBitmap(lado.coerceAtLeast(1), lado.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap = atual
            canvas = Canvas(atual)
            chave = null
        }
        if (chave == nova && !aguardandoPacote) return atual
        atual.eraseColor(fundo)
        desenhosParaTeste++
        aguardandoPacote = false
        val destino = canvas ?: Canvas(atual).also { canvas = it }
        val tamanho = Size(lado.toFloat(), lado.toFloat())
        escopo.draw(Density(densidade), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(destino), tamanho) {
            if (estilo == OrbStyle.PACK && pacote != null) {
                aguardandoPacote = pacote.frameFor(estado.toPackState(), tempoMs, lado, deterministico) == null
                if (aguardandoPacote) drawOrb(tempoMs, estado, OrbStyle.ATELIE, miolo) else drawPackOrb(tempoMs, estado, pacote, deterministic = deterministico)
            } else {
                drawOrb(tempoMs, estado, estilo, miolo)
            }
        }
        chave = nova
        return atual
    }
}
