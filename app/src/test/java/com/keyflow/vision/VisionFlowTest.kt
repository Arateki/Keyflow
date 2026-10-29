package com.keyflow.vision

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import org.junit.Test

class VisionFlowTest {
    @Test fun pausaAntesDoConsentimentoERestauraEmCadaSaida() {
        for (initial in listOf(ButtonState.RECORDING, ButtonState.PAUSED, ButtonState.IDLE_READY)) {
            val events = mutableListOf<String>()
            val flow = VisionFlow<String>({ events += "pausa" }, { events += "consentimento" },
                { events += "seletor" }, { events += "limpeza" }, onResult = { events += it })
            assertThat(flow.start(VisionFlowRequest(initial))).isTrue()
            if (initial == ButtonState.RECORDING) assertThat(events.take(2)).containsExactly("pausa", "consentimento").inOrder()
            flow.consentGranted(); flow.selected(); flow.captured("imagem")
            flow.finish()
            assertThat(events.count { it == "limpeza" }).isEqualTo(1)
            assertThat(flow.state).isEqualTo(VisionFlowState.NONE)
        }
    }

    @Test fun ignoraTransicoesEConsentimentoTardio() {
        var cleanup = 0
        val flow = VisionFlow<String>({}, {}, {}, { cleanup++ }, onResult = {})
        for (state in listOf(ButtonState.SENDING, ButtonState.CANCELLING)) assertThat(flow.start(VisionFlowRequest(state))).isFalse()
        assertThat(flow.start(VisionFlowRequest(ButtonState.IDLE_READY))).isTrue()
        flow.finish(); flow.consentGranted(); flow.finish()
        assertThat(cleanup).isEqualTo(1)
        assertThat(flow.state).isEqualTo(VisionFlowState.NONE)
    }
}
