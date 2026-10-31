package com.keyflow.computador.chaves

import com.keyflow.computador.ImpressaoDigital
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CodecChavesTest {
    private val c1 = ChaveSsh("k0000001", "Minha chave \"ç\"", TipoDeChave.ED25519, ImpressaoDigital.de(byteArrayOf(1, 2)), Protecao.KEYSTORE_ENVELOPE, true, 123L)
    private val c2 = ChaveSsh("k0000002", "P-256", TipoDeChave.ECDSA_P256, ImpressaoDigital.de(byteArrayOf(3)), Protecao.HARDWARE, false, 456L)

    @Test fun idaEVolta() = assertEquals(listOf(c1, c2), CodecChaves.decodificar(CodecChaves.codificar(listOf(c1, c2))))

    @Test fun lixoViraListaVazia() {
        for (s in listOf(null, "", "{", "[]", "{\"keys\":5}", "\"x\"")) assertEquals(emptyList<ChaveSsh>(), CodecChaves.decodificar(s))
        assertEquals(emptyList<ChaveSsh>(), CodecChaves.decodificar("[".repeat(100_000)))
    }

    @Test fun itemRuimEDescartadoEIdRepetidoViraUm() {
        val bom = JSONObject(CodecChaves.codificar(listOf(c1))).getJSONArray("keys").getJSONObject(0)
        val ruim = JSONObject(bom.toString()).put("fp", "SHA256:curto")
        val tipoRuim = JSONObject(bom.toString()).put("id", "outro").put("type", "DSA")
        assertEquals(listOf(c1), CodecChaves.decodificar("""{"keys":[ 7, $ruim, $tipoRuim, $bom, $bom ]}"""))
    }

    @Test fun jsonNaoTemMaterialSecretoNemCampoDeSegredo() {
        val txt = CodecChaves.codificar(listOf(c1, c2)).lowercase()
        for (p in listOf("private", "seed", "passphrase", "\"pem\"", "secret")) assertFalse(p, txt.contains(p))
    }

    @Test fun impressaoDeTextoRoundTrip() {
        val i = ImpressaoDigital.de(byteArrayOf(9, 9))
        assertEquals(i, ImpressaoDigital.deTexto(i.texto))
        assertEquals(null, ImpressaoDigital.deTexto("SHA256:@@@"))
        assertEquals(null, ImpressaoDigital.deTexto("MD5:abc"))
    }
}
