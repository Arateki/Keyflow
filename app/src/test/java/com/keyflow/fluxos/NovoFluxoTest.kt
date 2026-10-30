package com.keyflow.fluxos

import org.junit.Assert.*
import org.junit.Test

class NovoFluxoTest {
    private val vazia = ConfigFluxos(emptyList(), emptyList())
    @Test fun criaVazioSemAlterarConfiguracao() {
        val novo = novoFluxo(vazia, "Novo fluxo") { "novo" }!!
        assertEquals(Fluxo("novo", "Novo fluxo", etapas = emptyList()), novo)
        assertFalse(ValidadorFluxo.validar(novo).valido)
        assertTrue(vazia.fluxos.isEmpty()); assertTrue(vazia.gatilhos.isEmpty())
    }
    @Test fun escolhePrimeiroNomeLivre() {
        val config = vazia.copy(fluxos = listOf(Fluxo("a", "Novo fluxo", etapas = emptyList()), Fluxo("b", "Novo fluxo 2", etapas = emptyList())))
        assertEquals("Novo fluxo 3", novoFluxo(config, "Novo fluxo")!!.nome)
    }
    @Test fun vinteNaoPermiteCriar() {
        val config = vazia.copy(fluxos = (1..20).map { Fluxo("$it", "Fluxo $it", etapas = emptyList()) })
        assertNull(novoFluxo(config, "Novo fluxo") { error("não pode sortear id no limite") })
    }
    @Test fun idRepetidoNaoSubstituiFluxoExistente() {
        val config = vazia.copy(fluxos = listOf(Fluxo("id", "Meu", etapas = emptyList())))
        assertNotEquals("id", novoFluxo(config, "Novo fluxo") { "id" }!!.id)
        assertEquals("Meu", config.fluxos.single().nome)
    }
}
