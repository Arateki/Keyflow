package com.keyflow.vision

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.keyflow.core.ButtonState
import org.junit.Assert.assertThrows
import org.junit.Test

class VisionFlowTest {
    @Test fun pausaSomenteUmaVezAntesDoConsentimento() {
        for (initial in listOf(ButtonState.RECORDING, ButtonState.PAUSED, ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD)) {
            val events = mutableListOf<String>()
            val flow = VisionFlow<String>(
                pause = { events += "pausa" }, requestConsent = { events += "consentimento" },
                showSelector = {}, cleanup = {}, onResult = {},
            )
            assertThat(flow.start(VisionFlowRequest(initial))).isTrue()
            val expected = if (initial == ButtonState.RECORDING) listOf("pausa", "consentimento") else listOf("consentimento")
            assertWithMessage("entrada $initial").that(events).containsExactlyElementsIn(expected).inOrder()
            assertThat(flow.state).isEqualTo(VisionFlowState.CONSENT)
            flow.finish()
            // Sair da Visão não emite outra ação de gravação; a integração real prova o estado PAUSED.
            assertThat(events).containsExactlyElementsIn(expected).inOrder()
        }
    }

    @Test fun negacaoCancelamentoEConclusaoPercorremAsFasesELimpamUmaVez() {
        for (exit in listOf("negação", "cancelamento", "conclusão")) {
            val events = mutableListOf<String>()
            val phases = mutableListOf<VisionFlowState>()
            val flow = VisionFlow<String>({}, { events += "consentimento" }, { events += "seletor" },
                { events += "limpeza" }, onState = { phases += it }, onResult = { events += it })
            flow.start(VisionFlowRequest(ButtonState.PAUSED))
            if (exit != "negação") flow.consentGranted()
            if (exit == "conclusão") { flow.selected(); flow.captured("imagem") } else flow.finish()
            flow.finish(); flow.consentGranted(); flow.captured("tardio")
            val expected = when (exit) {
                "negação" -> listOf("consentimento", "limpeza")
                "cancelamento" -> listOf("consentimento", "seletor", "limpeza")
                else -> listOf("consentimento", "seletor", "imagem", "limpeza")
            }
            val expectedPhases = buildList {
                add(VisionFlowState.CONSENT)
                if (exit != "negação") add(VisionFlowState.SELECTING)
                if (exit == "conclusão") add(VisionFlowState.WORKING)
                add(VisionFlowState.NONE)
            }
            assertWithMessage(exit).that(events).containsExactlyElementsIn(expected).inOrder()
            assertWithMessage(exit).that(phases).containsExactlyElementsIn(expectedPhases).inOrder()
            assertThat(flow.state).isEqualTo(VisionFlowState.NONE)
        }
    }

    @Test fun ignoraTransicoesEAcionamentoDuplicado() {
        var consent = 0
        val flow = VisionFlow<String>({}, { consent++ }, {}, {}, onResult = {})
        for (state in listOf(ButtonState.SENDING, ButtonState.CANCELLING)) assertThat(flow.start(VisionFlowRequest(state))).isFalse()
        assertThat(flow.start(VisionFlowRequest(ButtonState.IDLE_READY))).isTrue()
        assertThat(flow.start(VisionFlowRequest(ButtonState.RECORDING))).isFalse()
        flow.captured("fora de ordem"); flow.selected()
        assertThat(consent).isEqualTo(1)
        assertThat(flow.state).isEqualTo(VisionFlowState.CONSENT)
    }

    @Test fun excecoesDoConsentimentoSeletorEConsumidorExecutamEpilogo() {
        for (stage in listOf("consentimento", "seletor", "consumidor")) {
            var cleanups = 0
            fun fail(at: String) { if (stage == at) error("falha simulada") }
            val flow = VisionFlow<String>({}, { fail("consentimento") }, { fail("seletor") },
                { cleanups++ }, onResult = { fail("consumidor") })
            assertThrows(IllegalStateException::class.java) {
                flow.start(VisionFlowRequest(ButtonState.IDLE_READY))
                flow.consentGranted(); flow.selected(); flow.captured("imagem")
            }
            flow.finish()
            assertThat(cleanups).isEqualTo(1)
            assertThat(flow.state).isEqualTo(VisionFlowState.NONE)
        }
    }

    // ── dono do resultado: Capturar de um fluxo (FLX-19) ─────────────────────

    private class Bitmapzinho(val nome: String) {
        var reciclado = false
    }

    @Test fun semDestinoExplicitoOResultadoVaiParaOConsumidorDeSempre() {
        val gestos = mutableListOf<String>()
        val flow = VisionFlow<String>({}, {}, {}, {}, onResult = { gestos += it })
        flow.start(VisionFlowRequest(ButtonState.IDLE_READY))
        flow.consentGranted(); flow.selected(); flow.captured("recorte")
        assertThat(gestos).containsExactly("recorte")
    }

    @Test fun comDestinoDeFluxoOValorChegaAoFluxoEONaoAoConsumidorDeGestos() {
        val gestos = mutableListOf<Bitmapzinho>()
        val fluxo = mutableListOf<Bitmapzinho?>()
        val cleanups = mutableListOf<String>()
        val flow = VisionFlow<Bitmapzinho>({}, {}, {}, { cleanups += "limpeza" }, onResult = { gestos += it; it.reciclado = true })
        assertThat(flow.start(VisionFlowRequest(ButtonState.IDLE_READY, DestinoCaptura.Fluxo<Bitmapzinho> { fluxo += it }))).isTrue()
        flow.consentGranted(); flow.selected()
        val recorte = Bitmapzinho("recorte")
        flow.captured(recorte)
        assertThat(fluxo).containsExactly(recorte)
        assertThat(gestos).isEmpty()
        // O Bitmap passa a ser do fluxo: o VisionFlow não o recicla, e o fim da etapa não entrega `null` por cima.
        assertThat(recorte.reciclado).isFalse()
        assertThat(cleanups).containsExactly("limpeza")
        assertThat(flow.state).isEqualTo(VisionFlowState.NONE)
    }

    @Test fun negarCancelarOuTerminarSemCapturaEntregaNullUmaVezSo() {
        for (saida in listOf("negação", "cancelamento do seletor", "falha da captura")) {
            val entregas = mutableListOf<String?>()
            val ordem = mutableListOf<String>()
            val flow = VisionFlow<String>({}, {}, {}, { ordem += "limpeza" }, onResult = { ordem += "gestos" })
            flow.start(VisionFlowRequest(ButtonState.IDLE_NO_FIELD, DestinoCaptura.Fluxo<String> { entregas += it; ordem += "entrega" }))
            if (saida != "negação") flow.consentGranted()
            if (saida == "falha da captura") flow.selected()
            flow.finish()
            flow.finish()
            assertThat(entregas).containsExactly(null)
            // O teclado e as views voltam antes de o fluxo seguir adiante.
            assertThat(ordem).containsExactly("limpeza", "entrega").inOrder()
        }
    }

    @Test fun excecaoNoConsentimentoDeUmFluxoEntregaNullAntesDeRelancar() {
        val entregas = mutableListOf<String?>()
        val flow = VisionFlow<String>({}, { error("falha simulada") }, {}, {}, onResult = {})
        assertThrows(IllegalStateException::class.java) {
            flow.start(VisionFlowRequest(ButtonState.IDLE_READY, DestinoCaptura.Fluxo<String> { entregas += it }))
        }
        assertThat(entregas).containsExactly(null)
    }

    @Test fun envioECancelamentoRecusamEFluxoNuncaPausaGravacaoEmCurso() {
        for (estado in listOf(ButtonState.SENDING, ButtonState.CANCELLING, ButtonState.RECORDING, ButtonState.PAUSED)) {
            var pausas = 0
            val entregas = mutableListOf<String?>()
            val flow = VisionFlow<String>({ pausas++ }, {}, {}, {}, onResult = {})
            assertThat(flow.start(VisionFlowRequest(estado, DestinoCaptura.Fluxo<String> { entregas += it }))).isFalse()
            assertThat(pausas).isEqualTo(0)
            assertThat(entregas).isEmpty()
            assertThat(flow.state).isEqualTo(VisionFlowState.NONE)
        }
    }

    @Test fun oDestinoDoPedidoAnteriorNaoVazaParaOSeguinte() {
        val fluxo = mutableListOf<String?>()
        val gestos = mutableListOf<String>()
        val flow = VisionFlow<String>({}, {}, {}, {}, onResult = { gestos += it })
        flow.start(VisionFlowRequest(ButtonState.IDLE_READY, DestinoCaptura.Fluxo<String> { fluxo += it }))
        flow.consentGranted(); flow.selected(); flow.captured("do fluxo")
        flow.start(VisionFlowRequest(ButtonState.IDLE_READY)) // gestos, como sempre
        flow.consentGranted(); flow.selected(); flow.captured("dos gestos")
        assertThat(fluxo).containsExactly("do fluxo")
        assertThat(gestos).containsExactly("dos gestos")
    }
}
