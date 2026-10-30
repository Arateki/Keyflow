package com.keyflow.core

import com.google.common.truth.Truth.assertThat
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.execucao.EstadoExecucao
import org.junit.After
import org.junit.Before
import org.junit.Test

class KeyFlowControllerTest {

    @Before
    fun resetController() {
        KeyFlowController.autoPasteInField = true
        KeyFlowController.autoPasteOnFocus = true
        KeyFlowController.registerInserter { true }
        KeyFlowController.flushPending()
        KeyFlowController.clearInserter()
    }

    @After
    fun resetFluxo() {
        KeyFlowController.updateFluxo(EstadoExecucao.Ocioso)
    }

    @Test
    fun `fluxo starts idle and follows the state the host publishes`() {
        assertThat(KeyFlowController.fluxo.value).isEqualTo(EstadoExecucao.Ocioso)

        val rodando = EstadoExecucao.Rodando("f1", 1, TipoEtapa.PERGUNTAR)
        KeyFlowController.updateFluxo(rodando)
        assertThat(KeyFlowController.fluxo.value).isEqualTo(rodando)

        KeyFlowController.updateFluxo(EstadoExecucao.Cancelado("f1", 1))
        assertThat(KeyFlowController.fluxo.value).isEqualTo(EstadoExecucao.Cancelado("f1", 1))
    }

    @Test
    fun `insertOrHold inserts immediately when target is available`() {
        val inserted = mutableListOf<String>()
        KeyFlowController.registerInserter {
            inserted += it
            true
        }

        val outcome = KeyFlowController.insertOrHold("hello")

        assertThat(outcome).isEqualTo(InsertOutcome.Inserted)
        assertThat(inserted).containsExactly("hello")
        assertThat(KeyFlowController.pending.value).isNull()
    }

    @Test
    fun `insertOrHold stores pending text when no target is available`() {
        val outcome = KeyFlowController.insertOrHold("later")

        assertThat(outcome).isEqualTo(InsertOutcome.Held)
        assertThat(KeyFlowController.pending.value).isEqualTo("later")
    }

    @Test
    fun `in-field recording with inField paste off goes to clipboard without pending`() {
        // Regressão: gravar com um campo focado e autoPasteInField desligado deve ir SÓ para a área de
        // transferência — nunca virar pendente, senão vazaria para o flush de onFocus ao reabrir o campo.
        KeyFlowController.autoPasteInField = false
        KeyFlowController.registerInserter { true } // campo focado na gravação

        val outcome = KeyFlowController.insertOrHold("typed-in-field")

        assertThat(outcome).isEqualTo(InsertOutcome.Held)
        assertThat(KeyFlowController.pending.value).isNull()
    }

    @Test
    fun `in-field recording is not held as pending when insertion is rejected`() {
        KeyFlowController.registerInserter { false } // campo focado, mas commit falha

        val outcome = KeyFlowController.insertOrHold("typed-in-field")

        assertThat(outcome).isEqualTo(InsertOutcome.Held)
        assertThat(KeyFlowController.pending.value).isNull()
    }

    @Test
    fun `registerInserter auto-flushes pending text immediately`() {
        KeyFlowController.insertOrHold("pending")
        val inserted = mutableListOf<String>()

        KeyFlowController.registerInserter {
            inserted += it
            true
        }

        // Auto-flush happens inside registerInserter — flushPending finds nothing left
        assertThat(inserted).containsExactly("pending")
        assertThat(KeyFlowController.pending.value).isNull()
        assertThat(KeyFlowController.flushPending()).isFalse()
    }

    @Test
    fun `flushPending inserts and clears pending text when called explicitly`() {
        KeyFlowController.insertOrHold("pending")
        // Register with inserter that rejects first call (simulates auto-flush failing)
        var callCount = 0
        KeyFlowController.registerInserter {
            callCount++
            false // reject during registerInserter → auto-flush fails → pending stays
        }
        assertThat(KeyFlowController.pending.value).isEqualTo("pending")

        // Now swap to an accepting inserter and flush manually
        val inserted = mutableListOf<String>()
        KeyFlowController.registerInserter {
            inserted += it
            true
        }

        assertThat(inserted).containsExactly("pending")
        assertThat(KeyFlowController.pending.value).isNull()
    }

    @Test
    fun `flushPending returns false and keeps pending text when no target is available`() {
        KeyFlowController.insertOrHold("pending")

        val flushed = KeyFlowController.flushPending()

        assertThat(flushed).isFalse()
        assertThat(KeyFlowController.pending.value).isEqualTo("pending")
    }

    @Test
    fun `targetAvailable follows inserter registration lifecycle`() {
        assertThat(KeyFlowController.targetAvailable.value).isFalse()

        KeyFlowController.registerInserter { true }

        assertThat(KeyFlowController.targetAvailable.value).isTrue()

        KeyFlowController.clearInserter()

        assertThat(KeyFlowController.targetAvailable.value).isFalse()
    }
}
