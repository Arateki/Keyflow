package com.keyflow.notificacao

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.notificacao.app.RenderizadorDeOrbe
import com.keyflow.ui.orb.OrbStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** NTF-T6: a orbe da notificação em bitmap — cada skin × estado, não vazio, determinístico, no tamanho pedido (NTF-25, NTF-29). */
@RunWith(AndroidJUnit4::class)
class RenderizadorDeOrbeTest {
    private val lado = 105
    private val skins = listOf(OrbStyle.ATELIE, OrbStyle.AERO, OrbStyle.GLITCH, OrbStyle.ROSE_GOLD, OrbStyle.MASCOT)

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    private fun opacos(b: Bitmap) = pixels(b).count { Color.alpha(it) > 16 }

    @Test
    fun cadaSkinEEstadoGeraBitmapNaoVazioDeterministicoNoTamanhoPedido() {
        for (skin in skins) for (ap in AparenciaDaOrbe.entries) {
            val a = RenderizadorDeOrbe.renderizar(skin, ap, lado)
            val b = RenderizadorDeOrbe.renderizar(skin, ap, lado)
            assertEquals("$skin/$ap largura", lado, a.width)
            assertEquals("$skin/$ap altura", lado, a.height)
            assertTrue("$skin/$ap vazio", opacos(a) > lado * lado / 20)
            assertTrue("$skin/$ap não é determinístico", pixels(a).contentEquals(pixels(b)))
        }
    }

    @Test
    fun oFundoFicaTransparenteNosCantos() {
        for (skin in skins) {
            val b = RenderizadorDeOrbe.renderizar(skin, AparenciaDaOrbe.LISTENING, lado)
            assertEquals("$skin canto", 0, Color.alpha(b.getPixel(0, 0)))
            assertEquals("$skin canto", 0, Color.alpha(b.getPixel(lado - 1, lado - 1)))
        }
    }

    @Test
    fun oGlitchNaoSomaComOFundo() {
        // O BlendMode.Plus soma cor sobre o que já existe: num bitmap transparente o resultado ainda precisa ter orbe e cantos limpos.
        val b = RenderizadorDeOrbe.renderizar(OrbStyle.GLITCH, AparenciaDaOrbe.IDLE, lado)
        assertTrue(opacos(b) > 0)
        assertEquals(0, Color.alpha(b.getPixel(2, 2)))
    }

    @Test
    fun osGlifosMudamOBitmapEOsEstadosSaoDiferentes() {
        val sem = pixels(RenderizadorDeOrbe.renderizar(OrbStyle.ATELIE, AparenciaDaOrbe.PAUSED, lado))
        for (g in GlifoDaOrbe.entries) {
            val com = pixels(RenderizadorDeOrbe.renderizar(OrbStyle.ATELIE, AparenciaDaOrbe.PAUSED, lado, g))
            assertTrue("glifo $g não aparece", !com.contentEquals(sem))
        }
        // Pausada reusa o desenho de Gravando (o glifo é o que as distingue); ocioso é outro desenho.
        val idle = pixels(RenderizadorDeOrbe.renderizar(OrbStyle.ATELIE, AparenciaDaOrbe.IDLE, lado))
        val listening = pixels(RenderizadorDeOrbe.renderizar(OrbStyle.ATELIE, AparenciaDaOrbe.LISTENING, lado))
        assertNotEquals(idle.toList(), listening.toList())
    }

    private fun corDominante(b: Bitmap): Int {
        val opacos = pixels(b).filter { Color.alpha(it) > 200 }
        require(opacos.isNotEmpty()) { "glifo não desenhou" }
        return opacos.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
    }

    @Test
    fun osGlifosDePacoteESistemaUsamATintaDaSkinTodos() {
        val verde = Color.rgb(0, 200, 0)
        for (g in GlifoDaOrbe.entries) {
            val b = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
            RenderizadorDeOrbe.glifoSobre(b, g, verde)
            val c = corDominante(b)
            assertEquals("glifo $g", verde, c)
        }
    }

    @Test
    fun asSkinsNativasMantemAsCoresProprias() {
        val atelie = RenderizadorDeOrbe.renderizar(OrbStyle.ATELIE, AparenciaDaOrbe.PAUSED, lado, GlifoDaOrbe.LIXEIRA)
        val ambar = Color.rgb(0xC9, 0x77, 0x2F)
        assertTrue(pixels(atelie).any { Color.alpha(it) > 200 && Math.abs(Color.red(it) - Color.red(ambar)) < 12 && Math.abs(Color.green(it) - Color.green(ambar)) < 12 })
    }

    @Test
    fun oEstiloDePacoteSemPacoteCaiNoAtelie() {
        val pacote = RenderizadorDeOrbe.renderizar(OrbStyle.PACK, AparenciaDaOrbe.IDLE, lado)
        val atelie = RenderizadorDeOrbe.renderizar(OrbStyle.ATELIE, AparenciaDaOrbe.IDLE, lado)
        assertTrue(pixels(pacote).contentEquals(pixels(atelie)))
    }
}
