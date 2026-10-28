package com.keyflow.vision.ocr

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** O recorte vem de fora do processo: o tamanho declarado é conferido e limitado ANTES de qualquer leitura de bytes. */
class RgbaImageTest {

    private val file = File.createTempFile("recorte", ".rgba")

    @After
    fun tearDown() {
        file.delete()
    }

    private fun refused(width: Int, height: Int): String {
        try {
            RgbaImage.checkRaw(file, width, height)
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        fail("${width}x$height devia ser recusado")
        return ""
    }

    @Test
    fun tamanhoCertoPassaEDevolveOsBytesEsperados() {
        file.writeBytes(ByteArray(6 * 5 * 4))
        assertEquals(120L, RgbaImage.checkRaw(file, 6, 5))
        assertEquals(6, RgbaImage.readRaw(file, 6, 5).width)
    }

    @Test
    fun arquivoDeOutroTamanhoEhRecusado() {
        file.writeBytes(ByteArray(10))
        assertTrue(refused(6, 5).contains("esperava 120"))
    }

    @Test
    fun acimaDoTetoDePixelsCaiAntesDeConferirOArquivoOuLer() {
        file.writeBytes(ByteArray(16))
        // 100 mil x 100 mil = 10 bilhões de px (40 GB de RGBA): a mensagem é a do teto, não a do tamanho do arquivo.
        assertTrue(refused(100_000, 100_000).contains("teto"))
        // Um pixel acima do teto de 12 MP.
        assertTrue(refused(12_001, 1_000).contains("teto"))
        // Estouro de Int na conta: tem de ser feita em Long.
        assertTrue(refused(Int.MAX_VALUE, Int.MAX_VALUE).contains("teto"))
        try {
            RgbaImage.readRaw(file, 100_000, 100_000)
            fail("readRaw também respeita o teto")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("teto"))
        }
    }

    @Test
    fun noTetoExatoAContaAindaConfereOArquivo() {
        file.writeBytes(ByteArray(16))
        // 12 MP exatos: passa do teto? Não; cai na conferência do tamanho do arquivo.
        assertTrue(refused(12_000, 1_000).contains("esperava"))
    }

    @Test
    fun telaCheiaDe4KCabeNoTeto() {
        assertTrue(3_840L * 2_160 <= RgbaImage.MAX_PIXELS)
        assertTrue(1_080L * 2_400 <= RgbaImage.MAX_PIXELS)
    }

    @Test
    fun imagemVaziaOuNegativaEhRecusada() {
        file.writeBytes(ByteArray(0))
        for ((w, h) in listOf(0 to 5, 5 to 0, -1 to 5)) assertTrue(refused(w, h).contains("vazia"))
    }
}
