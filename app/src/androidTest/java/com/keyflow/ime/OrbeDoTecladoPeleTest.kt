package com.keyflow.ime

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.OrbStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** KBO-21..23: o bitmap da pele da orbe do teclado (claro/escuro, tempo congelado, queda para o Ateliê). */
@RunWith(AndroidJUnit4::class)
class OrbeDoTecladoPeleTest {
    private val densidade = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    private val lado = 96
    private val trayEscuro = 0xFF332E27.toInt()
    private val trayClaro = 0xFFDCD0AC.toInt()

    private fun quadro(
        estilo: OrbStyle,
        fundo: Int = trayEscuro,
        tempoMs: Float = 2000f,
        estado: OrbState = OrbState.Idle,
        pele: OrbeDoTecladoPele = OrbeDoTecladoPele(densidade),
    ): Bitmap = pele.quadro(estilo, null, estado, lado, fundo, miolo = false, tempoMs = tempoMs).copy(Bitmap.Config.ARGB_8888, false)

    @Test
    fun ateliePorTemaClaroEEscuroNaoDaOMesmoBitmap() {
        assertFalse(quadro(OrbStyle.ATELIE, trayEscuro).sameAs(quadro(OrbStyle.ATELIE, trayClaro)))
    }

    @Test
    fun comTempoCongeladoOBitmapNaoMudaEntreDoisRuns() {
        for (estilo in listOf(OrbStyle.ATELIE, OrbStyle.AERO, OrbStyle.GLITCH, OrbStyle.ROSE_GOLD, OrbStyle.MASCOT)) {
            for (estado in OrbState.entries) {
                assertTrue("$estilo/$estado", quadro(estilo, estado = estado).sameAs(quadro(estilo, estado = estado)))
            }
        }
    }

    @Test
    fun semOGanchoOTempoAvancaAPele() {
        assertFalse(quadro(OrbStyle.ATELIE, tempoMs = 1000f).sameAs(quadro(OrbStyle.ATELIE, tempoMs = 2500f)))
    }

    @Test
    fun oMesmoQuadroNaoRefazOBitmap() {
        val pele = OrbeDoTecladoPele(densidade)
        quadro(OrbStyle.GLITCH, pele = pele)
        quadro(OrbStyle.GLITCH, pele = pele)
        assertEquals(1, pele.desenhosParaTeste)
        quadro(OrbStyle.GLITCH, tempoMs = 2100f, pele = pele)
        assertEquals(2, pele.desenhosParaTeste)
    }

    @Test
    fun semDireitoOuSemQuadrosDeOrbeADecisaoDesenhaOAtelie() {
        val atelie = quadro(OrbStyle.ATELIE)
        assertTrue(quadro(EscolhaDaPeleDaOrbe.estilo("aero", skinsUnlocked = false, temQuadrosDeOrbe = false)).sameAs(atelie))
        assertTrue(quadro(EscolhaDaPeleDaOrbe.estilo("pack:so-teclado", skinsUnlocked = true, temQuadrosDeOrbe = false)).sameAs(atelie))
        assertFalse(quadro(EscolhaDaPeleDaOrbe.estilo("aero", skinsUnlocked = true, temQuadrosDeOrbe = false)).sameAs(atelie))
    }
}
