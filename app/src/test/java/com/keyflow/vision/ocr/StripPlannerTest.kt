package com.keyflow.vision.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StripPlannerTest {

    private val params = OcrParams()

    private fun Strip.range() = if (vertical) y0 until y1 else x0 until x1

    @Test
    fun telaQuePequenaCabeNumaFaixaSo() {
        val strips = StripPlanner.plan(1080, 1080, params)
        assertEquals(1, strips.size)
        val s = strips.single()
        assertEquals(0, s.x0); assertEquals(0, s.y0); assertEquals(1080, s.x1); assertEquals(1080, s.y1)
        assertEquals(1088, s.mapW); assertEquals(1088, s.mapH) // 1080 → múltiplo de 32 mais próximo
    }

    @Test
    fun telaAltaDoA32ViraDuasFaixasSemEncolher() {
        val strips = StripPlanner.plan(720, 1600, params)
        assertEquals(2, strips.size)
        assertEquals(0 until 960, strips[0].range())
        assertEquals(896 until 1600, strips[1].range())
        // Sem redução: o texto de 14 px continua com 14 px (a razão região/mapa é ~1, só o arredondamento para 32).
        for (s in strips) {
            assertEquals(0, s.x0); assertEquals(720, s.x1)
            assertTrue("largura do mapa ${s.mapW}", s.mapW in 704..736)
            assertTrue(s.mapH % 32 == 0 && s.mapW % 32 == 0)
        }
        // Cada faixa possui o seu trecho: a divisa fica no meio da sobreposição.
        assertEquals(0, strips[0].ownStart); assertEquals(928, strips[0].ownEnd)
        assertEquals(928, strips[1].ownStart); assertEquals(1600, strips[1].ownEnd)
    }

    @Test
    fun telaCheia1080x2400TemTresFaixasEOsTrechosCobremTudoSemFuro() {
        val strips = StripPlanner.plan(1080, 2400, params)
        assertEquals(3, strips.size)
        assertEquals(0, strips.first().ownStart)
        assertEquals(2400, strips.last().ownEnd)
        for (i in 1 until strips.size) assertEquals(strips[i - 1].ownEnd, strips[i].ownStart)
        for (s in strips) {
            assertTrue("a faixa ${s.range()} contém o que possui", s.ownStart >= s.range().first && s.ownEnd <= s.range().last + 1)
            // Margem: a faixa enxerga pelo menos metade da sobreposição além do que possui (exceto nas pontas da imagem).
            if (s.ownStart > 0) assertTrue(s.ownStart - s.range().first >= 32)
            if (s.ownEnd < 2400) assertTrue(s.range().last + 1 - s.ownEnd >= 32)
        }
    }

    @Test
    fun paisagemDeTabletCorreAsFaixasNoEixoLargoEReduzOEixoCurto() {
        val strips = StripPlanner.plan(1920, 1200, params)
        assertFalse(strips.first().vertical)
        assertEquals(2, strips.size)
        for (s in strips) {
            assertEquals(0, s.y0); assertEquals(1200, s.y1)
            // 1200 > 1088: o eixo curto é reduzido para ~1088 (e só ele); o eixo largo vira faixas de ~960.
            assertTrue("mapH ${s.mapH}", s.mapH in 1056..1120)
            assertTrue(s.mapW <= 1024)
        }
        assertEquals(1920, strips.last().x1)
    }

    @Test
    fun restoPequenoDaUltimaFaixaFundeNaAnterior() {
        // 1900: as faixas dariam [0,960) [896,1856) [1792,1900) — a última (108 px) é só repetição.
        val strips = StripPlanner.plan(600, 1900, params)
        assertEquals(2, strips.size)
        assertEquals(1900, strips.last().y1)
        assertEquals(1900, strips.last().ownEnd)
    }

    @Test
    fun todoPixelDoEixoMaiorEPossuidoPorUmaFaixaSo() {
        for ((w, h) in listOf(720 to 1600, 1080 to 2400, 1080 to 2340, 360 to 800, 1200 to 1920, 1920 to 1200, 2560 to 1600, 500 to 5000)) {
            val strips = StripPlanner.plan(w, h, params)
            val long = maxOf(w, h)
            val owner = IntArray(long)
            for (s in strips) for (p in s.ownStart until s.ownEnd) owner[p]++
            assertTrue("$w x $h: posições sem dono ou com dois", owner.all { it == 1 })
            for (s in strips) {
                assertTrue(s.mapW % 32 == 0 && s.mapW >= 32)
                assertTrue(s.mapH % 32 == 0 && s.mapH >= 32)
            }
        }
    }

    @Test
    fun donoDaCaixaPeloCentro() {
        val strips = StripPlanner.plan(720, 1600, params)
        val inOverlapTop = TextBox(0, 925, 100, 945) // centro 935 ≥ 928 → da 2ª faixa
        assertFalse(strips[0].owns(inOverlapTop))
        assertTrue(strips[1].owns(inOverlapTop))
        assertTrue(strips[0].owns(TextBox(0, 905, 100, 925))) // centro 915
    }

    @Test
    fun mapSizeArredondaParaMultiploDe32ComMinimo() {
        assertEquals(32, StripPlanner.mapSize(1.0))
        assertEquals(32, StripPlanner.mapSize(47.0))
        assertEquals(64, StripPlanner.mapSize(48.0))
        assertEquals(960, StripPlanner.mapSize(960.0))
    }
}
