package com.keyflow.notificacao

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.notificacao.app.AparenciaDaNotificacao
import com.keyflow.notificacao.app.AparenciaIndisponivel
import com.keyflow.notificacao.app.FonteDeOrbe
import com.keyflow.notificacao.app.ProvedorDeAparencia
import com.keyflow.ui.orb.OrbStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/** NTF-T7: skin efetiva, Sistema, cache, claro/escuro e fallback (NTF-24, NTF-26, NTF-28, NTF-30). */
@RunWith(AndroidJUnit4::class)
class ProvedorDeAparenciaTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val lado = 105
    private val ocioso = EstadoVisual(AparenciaDaOrbe.IDLE, null, CronometroDaNotificacao.OCULTO, emptyList())

    private fun provedor(
        direito: () -> Boolean = { true },
        pacote: (String) -> FonteDeOrbe? = { null },
        versao: () -> Int = { 0 },
        render: ((OrbStyle, AparenciaDaOrbe, Int, GlifoDaOrbe?) -> Bitmap)? = null,
    ) = if (render == null) ProvedorDeAparencia(ctx, direito, pacote, versao) else ProvedorDeAparencia(ctx, direito, pacote, versao, render)

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }.toList()

    @Test
    fun skinPagaSemODireitoDevolveOAtelieENaoApagaAEscolha() {
        var direito = false
        val p = provedor(direito = { direito })
        assertEquals("atelie", p.skinEfetiva("glitch"))
        val sem = p.obterAgora("glitch", AparenciaDaOrbe.IDLE, null, lado, false)
        assertEquals(pixels(p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, false).orbe), pixels(sem.orbe))
        direito = true
        assertEquals("glitch", p.skinEfetiva("glitch"))
        p.invalidar()
        assertNotEquals(pixels(sem.orbe), pixels(p.obterAgora("glitch", AparenciaDaOrbe.IDLE, null, lado, false).orbe))
    }

    @Test
    fun sistemaMudaDeCorComOModoNoturnoEAtelieComOFundo() {
        val p = provedor()
        val claro = p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, escuro = false)
        val escuro = p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, escuro = true)
        assertEquals(ProvedorDeAparencia.FUNDO_CLARO, claro.fundo)
        assertEquals(ProvedorDeAparencia.FUNDO_ESCURO, escuro.fundo)
        val sis = p.obterAgora("sistema", AparenciaDaOrbe.IDLE, null, lado, false)
        assertNull("o Sistema usa o fundo do sistema", sis.fundo)
        assertTrue(Color.alpha(sis.orbe.getPixel(lado / 2, lado / 2)) > 200)
        assertEquals(0, Color.alpha(sis.orbe.getPixel(0, 0)))
        // a cor de destaque do sistema vale para o modo do aparelho: tinta legível (branca ou escura), não a do Ateliê
        assertTrue(sis.tinta == 0xFFFFFFFF.toInt() || sis.tinta == 0xFF1B1B1B.toInt())
    }

    @Test
    fun asPagasNaoPintamFundo() {
        val p = provedor()
        for (id in listOf("aero", "glitch", "rosegold", "mascot")) {
            assertNull("$id não pinta fundo", p.obterAgora(id, AparenciaDaOrbe.IDLE, null, lado, true).fundo)
        }
    }

    @Test
    fun skinInexistenteEFalhaDeRenderCaemNoAtelieEDepoisNaMinima() = runBlocking {
        val atelie = provedor().obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, false)
        assertEquals(pixels(atelie.orbe), pixels(provedor().obterAgora("nao-existe", AparenciaDaOrbe.IDLE, null, lado, false).orbe))
        // o pacote some: Ateliê
        assertEquals(pixels(atelie.orbe), pixels(provedor(pacote = { null }).obterAgora("pack:sumiu", AparenciaDaOrbe.IDLE, null, lado, false).orbe))
        // a skin falha ao desenhar, o Ateliê também → indisponível
        val explode = provedor(render = { s, _, _, _ -> if (s == OrbStyle.GLITCH) error("falhou") else Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888) })
        assertNotNull(explode.obter("glitch", ocioso, lado, false))
        val tudoFalha = provedor(render = { _, _, _, _ -> error("falhou") })
        try {
            tudoFalha.obter("glitch", ocioso, lado, false)
            fail("era para falhar")
        } catch (_: AparenciaIndisponivel) {
        }
    }

    @Test
    fun aChaveDoCacheMudaComSkinEstadoLadoEModo() {
        val p = provedor()
        p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, false)
        p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, false)
        assertEquals("a repetida vem do cache", 1, p.desenhos)
        p.obterAgora("aero", AparenciaDaOrbe.IDLE, null, lado, false)
        p.obterAgora("atelie", AparenciaDaOrbe.LISTENING, null, lado, false)
        p.obterAgora("atelie", AparenciaDaOrbe.IDLE, GlifoDaOrbe.X, lado, false)
        p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado + 1, false)
        p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, true)
        assertEquals(6, p.desenhos)
    }

    @Test
    fun aVersaoDosPacotesEODireitoInvalidam() = runBlocking {
        var versao = 0
        val p = provedor(versao = { versao })
        p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, false)
        versao = 1
        p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, false)
        assertEquals("SkinPackStore.changes muda a chave", 2, p.desenhos)

        val direito = MutableStateFlow(false)
        val pacotes = MutableStateFlow(0)
        val escopo = CoroutineScope(Dispatchers.Default)
        try {
            p.observar(escopo, direito, pacotes)
            delay(300)
            p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, false) // do cache (já invalidado pela coleta inicial? refaz)
            val antes = p.desenhos
            p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, false)
            assertEquals(antes, p.desenhos)
            direito.value = true
            delay(300)
            p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, false)
            assertEquals("skinsUnlockedFlow solta o cache", antes + 1, p.desenhos)
            pacotes.value = 1
            delay(300)
            p.obterAgora("atelie", AparenciaDaOrbe.IDLE, null, lado, false)
            assertEquals("SkinPackStore.changes solta o cache", antes + 2, p.desenhos)
        } finally {
            escopo.cancel()
        }
    }

    @Test
    fun umaFonteDePacoteDesenhaPelaInterface() {
        val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val fonte = object : FonteDeOrbe {
            override fun desenhar(aparencia: AparenciaDaOrbe, glifo: GlifoDaOrbe?, ladoPx: Int, escuro: Boolean) =
                AparenciaDaNotificacao(bmp, 0xFF112233.toInt(), null)
        }
        val a = provedor(pacote = { fonte }).obterAgora("pack:x", AparenciaDaOrbe.IDLE, null, lado, false)
        assertEquals(Color.RED, a.orbe.getPixel(3, 3))
        assertEquals(0xFF112233.toInt(), a.tinta)
    }
}
