package com.keyflow.computador

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CMP-05/CMP-16: o JSON de um host não tem campo de segredo nem o valor de um segredo plantado. */
class HostSemSegredoTest {
    private val proibidas = setOf("password", "key", "passphrase", "privatekey", "secret", "senha", "chave", "pem", "seed")

    private fun chaves(v: Any?, acc: MutableSet<String> = mutableSetOf()): Set<String> {
        when (v) {
            is JSONObject -> v.keys().forEach { k -> acc += k.lowercase(); chaves(v.get(k), acc) }
            is JSONArray -> for (i in 0 until v.length()) chaves(v.get(i), acc)
        }
        return acc
    }

    @Test fun jsonDoHostNaoTemCampoDeSegredo() {
        val host = Host("a1b2c3d4", "n", "x.org", 22, "u", AutenticacaoHost.Chave("k0000001"), lembrarSenha = true, agente = PoliticaAgente.PERMITIDO)
        val achadas = chaves(JSONObject(CodecHosts.codificar(ConfigHosts(listOf(host))))).intersect(proibidas)
        assertTrue("campo de segredo no JSON do host: $achadas", achadas.isEmpty())
    }

    @Test fun valorDeSegredoPlantadoNaoApareceNoJsonNemNoToString() {
        val senha = "SENTINELA-senha-9f8e7d6c"
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nSENTINELA-chave-1a2b3c\n-----END OPENSSH PRIVATE KEY-----"
        for (tipo in listOf("password", "key")) {
            val r = MigracaoHostUnico.migrar(HostUnicoLegado("srv.example.org", 22, "yan", tipo, senha, pem))!!
            val json = CodecHosts.codificar(r.config)
            for (s in listOf("SENTINELA", "BEGIN OPENSSH")) {
                assertFalse("$s no JSON ($tipo)", json.contains(s))
                assertFalse("$s no toString do resultado ($tipo)", r.toString().contains(s) || r.segredos.toString().contains(s))
                assertFalse("$s no toString do host ($tipo)", r.config.hosts.single().toString().contains(s))
            }
        }
    }

    @Test fun hostNaoTemPropriedadeDeSegredo() {
        val nomes = Host::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("$nomes", nomes.none { it in proibidas || it.contains("passphrase") })
    }
}
