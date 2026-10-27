package com.keyflow.vision.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DbPostprocessTest {

    private fun map(w: Int, h: Int, fill: (FloatArray) -> Unit): FloatArray = FloatArray(w * h).also(fill)

    private fun FloatArray.rect(w: Int, x0: Int, y0: Int, x1: Int, y1: Int, v: Float) {
        for (y in y0 until y1) for (x in x0 until x1) this[y * w + x] = v
    }

    @Test
    fun doisRetangulosViramDuasCaixasComAMediaComoPontuacao() {
        val w = 96
        val h = 64
        val prob = map(w, h) {
            it.rect(w, 10, 10, 50, 22, 0.9f)
            it.rect(w, 10, 40, 70, 52, 0.8f)
        }
        val boxes = DbPostprocess.components(prob, w, h, 0.3f, minSide = 3, maxBoxes = 1000)
        assertEquals(2, boxes.size)
        val a = boxes.single { it.y0 == 10 }
        assertEquals(10, a.x0); assertEquals(50, a.x1); assertEquals(22, a.y1)
        assertEquals(0.9f, a.score, 1e-5f)
        assertEquals(0.8f, boxes.single { it.y0 == 40 }.score, 1e-5f)
    }

    @Test
    fun abaixoDoLimiarNaoEComponenteEPequenoDemaisCai() {
        val w = 64
        val h = 64
        val prob = map(w, h) {
            it.rect(w, 5, 5, 40, 20, 0.2f)   // abaixo de 0,3
            it.rect(w, 5, 30, 8, 33, 0.9f)   // 3x3: passa o lado mínimo mas tem 9 px
            it.rect(w, 20, 30, 22, 50, 0.9f) // 2 de largura: lado mínimo 3 → cai
        }
        val boxes = DbPostprocess.components(prob, w, h, 0.3f, minSide = 3, maxBoxes = 1000)
        assertEquals(1, boxes.size)
        assertEquals(5, boxes.single().x0)
        assertEquals(30, boxes.single().y0)
    }

    @Test
    fun diagonalLigaPorOitoVizinhos() {
        val w = 40
        val h = 40
        val prob = map(w, h) {
            for (i in 0 until 12) {
                it[(10 + i) * w + (10 + i)] = 0.9f
                it[(10 + i) * w + (11 + i)] = 0.9f // engorda para o lado mínimo passar
                it[(11 + i).coerceAtMost(h - 1) * w + (10 + i)] = 0.9f
            }
        }
        val boxes = DbPostprocess.components(prob, w, h, 0.3f, minSide = 3, maxBoxes = 1000)
        assertEquals(1, boxes.size)
    }

    @Test
    fun limiteDeCandidatosFicaComAsMaisPontuadas() {
        val w = 120
        val h = 20
        val prob = map(w, h) {
            for (i in 0 until 6) it.rect(w, i * 20, 2, i * 20 + 10, 14, 0.5f + i * 0.05f)
        }
        val boxes = DbPostprocess.components(prob, w, h, 0.3f, minSide = 3, maxBoxes = 3)
        assertEquals(3, boxes.size)
        assertTrue("as 3 mais pontuadas (0,65, 0,70, 0,75): ${boxes.map { it.score }}", boxes.all { it.score >= 0.64f })
    }

    @Test
    fun unclipAfastaCadaLadoPorAreaVezesRazaoSobrePerimetro() {
        val box = DbPostprocess.MapBox(100, 50, 200, 70, 0.9f) // 100 x 20
        val e = DbPostprocess.unclip(box, 1.5f)
        val d = 100f * 20f * 1.5f / (2f * 120f) // 12,5
        assertEquals(100f - d, e[0], 1e-4f); assertEquals(50f - d, e[1], 1e-4f)
        assertEquals(200f + d, e[2], 1e-4f); assertEquals(70f + d, e[3], 1e-4f)
    }

    @Test
    fun caixasVoltamParaACoordenadaDaImagemDeOrigemComClamp() {
        // Faixa [900,1600) de uma imagem 720x1600; mapa 736x704 (escala 720/736 em x e 700/704 em y).
        val strip = Strip(0, 900, 720, 1600, 736, 704, 928, 1600, true)
        val params = OcrParams(unclipRatio = 0f, minBoxSide = 3) // sem unclip: só a conversão de coordenadas
        val prob = map(strip.mapW, strip.mapH) { it.rect(strip.mapW, 100, 100, 300, 130, 0.9f) }
        val box = DbPostprocess.boxesFor(strip, prob, 720, 1600, params).single()
        assertEquals(Math.round(100 * 720f / 736), box.x0)
        assertEquals(Math.round(900 + 100 * 700f / 704), box.y0)
        assertEquals(Math.round(900 + 130 * 700f / 704), box.y1)
        assertTrue(box.x1 <= 720 && box.y1 <= 1600)
        // Mapa que cobre a faixa inteira com unclip: clampa na imagem, nunca sai dela.
        val full = map(strip.mapW, strip.mapH) { it.rect(strip.mapW, 0, 0, strip.mapW, strip.mapH, 0.95f) }
        val b = DbPostprocess.boxesFor(strip, full, 720, 1600, OcrParams()).single()
        assertTrue(b.x0 >= 0 && b.y0 >= 0 && b.x1 <= 720 && b.y1 <= 1600)
    }

    @Test
    fun pontuacaoAbaixoDoLimiteDeCaixaDescartaACaixa() {
        val strip = Strip(0, 0, 200, 100, 192, 96, 0, 100, true)
        val prob = map(192, 96) { it.rect(192, 20, 20, 150, 50, 0.45f) }
        assertEquals(0, DbPostprocess.boxesFor(strip, prob, 200, 100, OcrParams(boxThreshold = 0.6f)).size)
        assertEquals(1, DbPostprocess.boxesFor(strip, prob, 200, 100, OcrParams(boxThreshold = 0.4f)).size)
    }

    @Test
    fun sobreposicaoDeFaixasNaoDuplicaACaixa() {
        val top = Strip(0, 0, 100, 960, 96, 960, 0, 928, true)
        val bottom = Strip(0, 896, 100, 1600, 96, 704, 928, 1600, true)
        val inBoth = TextBox(0, 925, 80, 955) // centro 940: só a de baixo possui
        val onlyTop = TextBox(0, 100, 80, 130)
        val onlyBottom = TextBox(0, 1200, 80, 1230)
        val merged = DbPostprocess.mergeStrips(
            listOf(top to listOf(onlyTop, inBoth), bottom to listOf(inBoth, onlyBottom)),
        )
        assertEquals(3, merged.size)
        assertEquals(1, merged.count { it.y0 == 925 })
    }

    @Test
    fun caixaQuaseToraDentroDeOutraSai() {
        val strip = Strip(0, 0, 400, 400, 416, 416, 0, 400, true)
        val big = TextBox(10, 10, 300, 60)
        val inside = TextBox(20, 15, 200, 55)    // toda dentro da grande
        val beside = TextBox(10, 100, 300, 150)  // separada
        val merged = DbPostprocess.mergeStrips(listOf(strip to listOf(inside, beside, big)))
        assertEquals(2, merged.size)
        assertTrue(merged.contains(big) && merged.contains(beside))
    }
}
