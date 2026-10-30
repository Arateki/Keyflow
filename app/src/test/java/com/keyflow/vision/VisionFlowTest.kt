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
}
