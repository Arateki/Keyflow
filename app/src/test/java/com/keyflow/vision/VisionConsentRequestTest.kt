package com.keyflow.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisionConsentRequestTest {
    @Test fun negacaoEntregaNuloUmaVezELiberaProximoAcionamento() {
        val requests = VisionConsentRequest<String>()
        val results = mutableListOf<String?>()
        val id = requests.begin { results += it }!!
        assertThat(requests.begin {}).isNull()
        requests.complete(id, null)
        requests.complete(id, "token reutilizado")
        assertThat(results).containsExactly(null)
        assertThat(requests.begin {}).isNotNull()
    }

    @Test fun respostaCanceladaNaoAutorizaNovaSessao() {
        val requests = VisionConsentRequest<String>()
        val results = mutableListOf<String?>()
        val old = requests.begin { results += "antigo" }!!
        requests.cancel()
        val current = requests.begin { results += it }!!
        requests.complete(old, "token antigo")
        assertThat(results).isEmpty()
        requests.complete(current, "token novo")
        assertThat(results).containsExactly("token novo")
    }
}
