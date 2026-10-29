package com.keyflow.vision

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode
import org.junit.Assert.assertThrows
import org.junit.Test

class VisionFlowTest {
    @Test fun pausaAntesDoConsentimentoEPreservaEstadoEmTodasAsSaidas() {
        for (mode in RecordingMode.entries) {
            for (initial in listOf(ButtonState.RECORDING, ButtonState.PAUSED, ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD)) {
                for (exit in listOf("conclusão", "cancelamento", "negação", "revogação", "erro", "modelo ausente")) {
                    var recording = initial
                    val events = mutableListOf<String>()
                    val flow = VisionFlow<String>(
                        pause = { events += "pausa"; recording = ButtonState.PAUSED },
                        requestConsent = { events += "consentimento" },
                        showSelector = { events += "seletor" },
                        cleanup = { events += "limpeza" }, onResult = { events += it },
                    )
                    assertThat(flow.start(VisionFlowRequest(recording))).isTrue()
                    if (initial == ButtonState.RECORDING) assertThat(events.take(2)).containsExactly("pausa", "consentimento").inOrder()
                    if (exit != "negação") flow.consentGranted()
                    if (exit == "conclusão" || exit == "erro") flow.selected()
                    if (exit == "conclusão") flow.captured("imagem") else flow.finish()
                    flow.finish(); flow.consentGranted()
                    assertThat(events.count { it == "limpeza" }).isEqualTo(1)
                    assertWithMessage("$mode / $initial / $exit").that(recording).isEqualTo(
                        if (initial == ButtonState.RECORDING) ButtonState.PAUSED else initial,
                    )
                    assertThat(flow.state).isEqualTo(VisionFlowState.NONE)
                }
            }
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
