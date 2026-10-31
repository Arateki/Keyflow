package com.keyflow.skins

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.keyflow.notificacao.AparenciaDaOrbe
import com.keyflow.notificacao.GlifoDaOrbe
import com.keyflow.skins.SkinPackFixtures.manifest
import com.keyflow.skins.SkinPackFixtures.svg
import com.keyflow.skins.SkinPackFixtures.zip
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** NTF-T8: a orbe que o pacote dá à notificação — o SVG de cor sólida, o 1º quadro da orbe e as abas que só listam quem tem a peça (NTF-31..35). */
class OrbeDePacoteTest {
    private lateinit var base: File
    private lateinit var store: SkinPackStore
    private val lado = 40

    @Before
    fun setUp() {
        base = SkinPackFixtures.tempRoot(ApplicationProvider.getApplicationContext<Context>().cacheDir)
        store = SkinPackStore(File(base, "skins"))
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun instalar(entries: List<Pair<String, String>>): SkinPack {
        val r = store.import(zip(entries).inputStream())
        assertTrue(r.report.errors.toString(), r.report.errors.isEmpty())
        return store.load(r.packId!!)!!
    }

    @Test
    fun umSvgDeCorSolidaDevolveOPixelEsperadoComATintaEOFundoDoManifest() {
        val extra = ""","notification":{"ink":"#112233","background":"#2B2720"}"""
        val pack = instalar(listOf("manifest.json" to manifest("N", extra), "notification_idle.svg" to svg("FF0000"), "notification_listening.svg" to svg("0000FF")))
        val o = OrbeDePacote(pack)
        val idle = o.desenhar(AparenciaDaOrbe.IDLE, null, lado, escuro = false)!!
        assertEquals(Color.RED, idle.orbe.getPixel(lado / 2, lado / 2))
        assertEquals(0xFF112233.toInt(), idle.tinta)
        assertEquals(0xFF2B2720.toInt(), idle.fundo)
        assertEquals(Color.BLUE, o.desenhar(AparenciaDaOrbe.LISTENING, null, lado, true)!!.orbe.getPixel(lado / 2, lado / 2))
        // estado ausente usa idle; igual nos modos claro e escuro
        assertEquals(Color.RED, o.desenhar(AparenciaDaOrbe.PAUSED, null, lado, true)!!.orbe.getPixel(lado / 2, lado / 2))
        assertEquals(0xFF2B2720.toInt(), o.desenhar(AparenciaDaOrbe.IDLE, null, lado, true)!!.fundo)
    }

    @Test
    fun oGlifoVaiPorCimaNaTintaDoPacote() {
        val pack = instalar(listOf("manifest.json" to manifest("N"), "notification_idle.svg" to svg("FF0000")))
        val o = OrbeDePacote(pack)
        val sem = o.desenhar(AparenciaDaOrbe.PAUSED, null, lado, false)!!.orbe
        val com = o.desenhar(AparenciaDaOrbe.PAUSED, GlifoDaOrbe.PAUSA, lado, false)!!.orbe
        assertFalse(sem.sameAs(com))
    }

    @Test
    fun umPacoteSoDeOrbeEmCopiarDesenhaOPrimeiroQuadroSemFundo() {
        val extra = ""","orb":{"centerInk":"#00FF00"}"""
        val pack = instalar(listOf("manifest.json" to manifest("O", extra), "orb_idle_01.svg" to svg("FF0000"), "orb_idle_02.svg" to svg("0000FF")))
        assertFalse(pack.hasNotification)
        val a = OrbeDePacote(pack).desenhar(AparenciaDaOrbe.IDLE, null, lado, false)!!
        assertEquals("o 1º quadro, não o 2º", Color.RED, a.orbe.getPixel(lado / 2, lado / 2))
        assertEquals(0xFF00FF00.toInt(), a.tinta)
        assertNull(a.fundo)
    }

    @Test
    fun umPacoteSoDeTecladoNaoTemOrbeParaDar() {
        val pack = instalar(listOf("manifest.json" to manifest("K"), "keyboard_key_normal.svg" to svg("FFFFFF")))
        assertNull(OrbeDePacote(pack).desenhar(AparenciaDaOrbe.IDLE, null, lado, false))
    }

    @Test
    fun oStoreInstalaListaRemoveEReimportaOPacoteComNotificacao() {
        val entries = listOf("manifest.json" to manifest("Noti"), "notification_idle.svg" to svg("FF0000"), "notification_paused.svg" to svg("00FF00"))
        val r = store.import(zip(entries).inputStream())
        assertTrue(r.report.installed.toString(), r.report.installed.any { it == "notificação: 2 estados" })
        val id = r.packId!!
        val p = store.load(id)!!
        assertTrue(p.hasNotification)
        assertFalse(p.hasOrb)
        assertFalse(p.hasKeyboard)
        assertNotNull(p.notificationFile(OrbPackState.LISTENING)) // usa idle
        assertEquals(1, store.list().size)
        val de = store.import(zip(entries + ("notification_transcribing.svg" to svg("0000FF"))).inputStream())
        assertTrue(de.replaced)
        assertEquals(id, de.packId)
        assertEquals(3, run { var n = 0; for (s in OrbPackState.entries) if (store.load(id)!!.notificationFile(s)?.name == "notification_${s.key}.svg") n++; n })
        assertTrue(store.remove(id))
        assertNull(store.load(id))
    }

    @Test
    fun asAbasSobreporETecladoContinuamListandoSoQuemTemAPecaDelas() {
        instalar(listOf("manifest.json" to manifest("So notificacao"), "notification_idle.svg" to svg()))
        instalar(listOf("manifest.json" to manifest("Com orbe"), "orb_idle_01.svg" to svg()))
        instalar(listOf("manifest.json" to manifest("Com teclado"), "keyboard_key_normal.svg" to svg()))
        val todos = store.list()
        assertEquals(listOf("Com orbe"), todos.filter { it.hasOrb }.map { it.name })
        assertEquals(listOf("Com teclado"), todos.filter { it.hasKeyboard }.map { it.name })
        assertEquals(listOf("So notificacao"), todos.filter { it.hasNotification }.map { it.name })
    }

    @Test
    fun oSvgNaoQuadradoDaNotificacaoAvisa() {
        val r = store.import(zip(listOf("manifest.json" to manifest("N"), "notification_idle.svg" to svg(viewBox = "0 0 100 60"))).inputStream())
        assertTrue(r.report.errors.isEmpty())
        assertTrue(r.report.warnings.toString(), r.report.warnings.any { it.contains("notification_idle.svg") && it.contains("quadrado") })
    }
}
