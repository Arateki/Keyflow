package com.keyflow.fluxos

import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Para QUALQUER configuração: o restaurar nunca encosta nos gatilhos nem nos fluxos da pessoa, é idempotente, respeita o limite
 * de 20 e deixa todo padrão que coube igual ao catálogo. Usa os geradores do codec (fluxos com `padrao` do catálogo ou nulo,
 * etapas e modelos quaisquer, gatilhos de qualquer superfície, inclusive de fluxo inexistente).
 */
class RestauradorPropertyTest {

    private var n = 0
    private fun idNovo() = "r${n++}"

    @Test
    fun gatilhosNeverChange() = runBlocking<Unit> {
        checkAll(ITERATIONS, CodecFluxosPropertyTest.config) { c ->
            assertEquals(c.gatilhos, RestauradorFluxos.restaurar(c, ::idNovo).gatilhos)
        }
    }

    @Test
    fun fluxosOfThePersonStayIdenticalAndInTheSameRelativeOrder() = runBlocking<Unit> {
        checkAll(ITERATIONS, CodecFluxosPropertyTest.config) { c ->
            val depois = RestauradorFluxos.restaurar(c, ::idNovo)
            assertEquals(c.fluxos.filter { it.padrao == null }, depois.fluxos.filter { it.padrao == null })
        }
    }

    @Test
    fun restoringTwiceIsRestoringOnce() = runBlocking<Unit> {
        checkAll(ITERATIONS, CodecFluxosPropertyTest.config) { c ->
            val uma = RestauradorFluxos.restaurar(c, ::idNovo)
            assertEquals(uma, RestauradorFluxos.restaurar(uma, ::idNovo))
            assertTrue(RestauradorFluxos.previa(uma).vazia)
        }
    }

    @Test
    fun everyDefaultThatFitsEndsEqualToTheCatalogAndTheLimitHolds() = runBlocking<Unit> {
        val cheias = Arb.list(CodecFluxosPropertyTest.fluxo, 0..LIMITES.FLUXOS).map { l -> l.mapIndexed { i, f -> f.copy(id = "${f.id}-$i") } }
        checkAll(ITERATIONS, cheias) { fluxos ->
            val c = ConfigFluxos(fluxos, emptyList())
            val depois = RestauradorFluxos.restaurar(c, ::idNovo)
            assertTrue("passou do limite", depois.fluxos.size <= maxOf(LIMITES.FLUXOS, fluxos.size))
            assertEquals("ids repetidos", depois.fluxos.map { it.id }.distinct().size, depois.fluxos.size)
            for (padrao in PadroesFluxo.CATALOGO) {
                // a restauração age no primeiro fluxo de cada padrão; ele termina igual ao catálogo
                val primeiro = depois.fluxos.firstOrNull { it.padrao == padrao.builtinId } ?: continue
                assertEquals(padrao.nome, primeiro.nome)
                assertEquals(padrao.etapas, primeiro.etapas)
            }
            val faltam = PadroesFluxo.CATALOGO.count { p -> fluxos.none { it.padrao == p.builtinId } }
            val cabem = minOf(faltam, maxOf(0, LIMITES.FLUXOS - fluxos.size))
            assertEquals("recriados", fluxos.size + cabem, depois.fluxos.size)
        }
    }

    companion object {
        const val ITERATIONS = 2_000
    }
}
