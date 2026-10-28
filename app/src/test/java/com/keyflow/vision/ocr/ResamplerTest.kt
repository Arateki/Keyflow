package com.keyflow.vision.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {

    private fun image(w: Int, h: Int, pixel: (x: Int, y: Int) -> Triple<Int, Int, Int>): RgbaImage {
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val (r, g, b) = pixel(x, y)
            val o = (y * w + x) * 4
            rgba[o] = r.toByte(); rgba[o + 1] = g.toByte(); rgba[o + 2] = b.toByte(); rgba[o + 3] = 0xFF.toByte()
        }
        return RgbaImage(w, h, rgba)
    }

    @Test
    fun corConstanteContinuaConstanteEmQualquerEscala() {
        val img = image(50, 30) { _, _ -> Triple(200, 100, 10) }
        for ((dw, dh) in listOf(25 to 15, 100 to 60, 7 to 48, 50 to 30)) {
            val out = Resampler.toPlanes(img, 0, 0, 50, 30, dw, dh, bgr = false)
            val plane = dw * dh
            for (i in 0 until plane) {
                assertEquals(200f, out[i], 1e-3f)
                assertEquals(100f, out[plane + i], 1e-3f)
                assertEquals(10f, out[2 * plane + i], 1e-3f)
            }
        }
    }

    @Test
    fun ordemBgrTrocaOPrimeiroEOUltimoPlano() {
        val img = image(8, 8) { _, _ -> Triple(255, 128, 0) }
        val rgb = Resampler.toPlanes(img, 0, 0, 8, 8, 8, 8, bgr = false)
        val bgr = Resampler.toPlanes(img, 0, 0, 8, 8, 8, 8, bgr = true)
        assertEquals(255f, rgb[0], 1e-3f); assertEquals(0f, rgb[2 * 64], 1e-3f)
        assertEquals(0f, bgr[0], 1e-3f); assertEquals(255f, bgr[2 * 64], 1e-3f)
        assertEquals(128f, bgr[64], 1e-3f)
    }

    @Test
    fun reducaoPorDoisFazMediaDeCadaBloco() {
        // Xadrez de preto e branco em blocos de 1 px: ao reduzir por 2 cada pixel novo é a média (cinza ~127,5).
        val img = image(16, 16) { x, y -> if ((x + y) % 2 == 0) Triple(255, 255, 255) else Triple(0, 0, 0) }
        val out = Resampler.toPlanes(img, 0, 0, 16, 16, 8, 8, bgr = false)
        for (i in 0 until 64) assertEquals(127.5f, out[i], 20f)
        // O interior (longe das bordas, onde o filtro é cheio) fica bem perto da média exata.
        assertEquals(127.5f, out[4 * 8 + 4], 1f)
    }

    @Test
    fun recorteLeSoARegiaoPedida() {
        // Metade esquerda vermelha, direita azul; recortar a direita dá só azul.
        val img = image(20, 10) { x, _ -> if (x < 10) Triple(255, 0, 0) else Triple(0, 0, 255) }
        val out = Resampler.toPlanes(img, 10, 0, 20, 10, 10, 10, bgr = false)
        for (i in 0 until 100) {
            assertEquals(0f, out[i], 1e-3f)
            assertEquals(255f, out[200 + i], 1e-3f)
        }
    }

    @Test
    fun ampliacaoEMonotonaNumDegrade() {
        val img = image(10, 1) { x, _ -> Triple(x * 25, 0, 0) }
        val out = Resampler.toPlanes(img, 0, 0, 10, 1, 40, 4, bgr = false)
        for (y in 0 until 4) for (x in 1 until 40) {
            assertTrue("não decresce em x=$x", out[y * 40 + x] >= out[y * 40 + x - 1] - 1e-3f)
        }
    }

    @Test
    fun regiaoForaDaImagemEErro() {
        val img = image(10, 10) { _, _ -> Triple(0, 0, 0) }
        for (bad in listOf(listOf(0, 0, 11, 10), listOf(5, 5, 5, 8), listOf(-1, 0, 4, 4))) {
            try {
                Resampler.toPlanes(img, bad[0], bad[1], bad[2], bad[3], 4, 4, false)
                throw AssertionError("região inválida $bad devia falhar")
            } catch (e: IllegalArgumentException) {
                // esperado
            }
        }
    }

    @Test
    fun imagemRgbaConfereOTamanhoDosBytes() {
        try {
            RgbaImage(4, 4, ByteArray(10))
            throw AssertionError("tamanho que não bate")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("RGBA"))
        }
    }

    @Test
    fun arquivoCruIdaEVolta() {
        val img = image(6, 5) { x, y -> Triple(x * 40, y * 50, 7) }
        val file = java.io.File.createTempFile("crop", ".rgba")
        try {
            img.writeRaw(file)
            val back = RgbaImage.readRaw(file, 6, 5)
            assertTrue(back.rgba.contentEquals(img.rgba))
            try {
                RgbaImage.readRaw(file, 6, 6)
                throw AssertionError("tamanho declarado errado")
            } catch (e: IllegalArgumentException) {
                // esperado
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun normalizacaoAplicaMediaEDesvioPorCanal() {
        val planes = floatArrayOf(0f, 255f, 127.5f, /*G*/ 0f, 255f, 127.5f, /*B*/ 0f, 255f, 127.5f)
        Normalization.apply(planes, 3, Normalization.HALF_RGB)
        assertEquals(-1f, planes[0], 1e-5f); assertEquals(1f, planes[1], 1e-5f); assertEquals(0f, planes[2], 1e-5f)
        val unit = floatArrayOf(255f, 0f, 51f, 255f, 0f, 51f, 255f, 0f, 51f)
        Normalization.apply(unit, 3, Normalization.UNIT_RGB)
        assertEquals(1f, unit[0], 1e-5f); assertEquals(0.2f, unit[2], 1e-5f)
        val net = floatArrayOf(255f, 255f, 255f, 255f, 255f, 255f, 255f, 255f, 255f)
        Normalization.apply(net, 3, Normalization.IMAGENET_RGB)
        assertEquals((1f - 0.485f) / 0.229f, net[0], 1e-4f)
        assertEquals((1f - 0.456f) / 0.224f, net[3], 1e-4f)
        assertEquals((1f - 0.406f) / 0.225f, net[6], 1e-4f)
    }
}
