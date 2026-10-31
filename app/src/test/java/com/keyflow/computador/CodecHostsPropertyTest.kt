package com.keyflow.computador

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** `decodificar(codificar(c)) == c` para QUALQUER configuração que a validação aceita (nomes com aspas, acentos, emoji…). */
class CodecHostsPropertyTest {
    private val nomes = Arb.string(1..40).map { it.trim().ifEmpty { "x" }.take(40) }
    private val enderecos = Arb.element("dev.example.org", "localhost", "10.1.2.3", "[::1]", "[2001:db8::1]", "a-b.c.d")
    private val usuarios = Arb.string(1..64).map { u -> u.filter { !it.isWhitespace() && !it.isISOControl() }.ifEmpty { "u" } }
    private val auths = Arb.element<AutenticacaoHost>(AutenticacaoHost.Senha, AutenticacaoHost.Perguntar, AutenticacaoHost.Chave("k0000001"), AutenticacaoHost.Chave("k-9_x"))

    private val ids = Arb.int().map { "%08x".format(it) }
    private val hostsArb = Arb.bind(
        ids, nomes, enderecos, Arb.int(1..65535), usuarios, auths, Arb.boolean(), Arb.boolean(), Arb.element(PoliticaImagens.entries),
    ) { id, nome, endereco, porta, usuario, auth, lembrar, agente, imagens ->
        val h = Host(id, nome, endereco, porta, usuario, auth, lembrar, if (agente) PoliticaAgente.PERMITIDO else PoliticaAgente.PROIBIDO, imagens)
        // O codec aplica a regra do agente; o gerador só produz o que o app produziria.
        if (h.agentePodeUsar && RegraAgente.podeMarcar(h) !is PodeMarcar.Sim) h.copy(agente = PoliticaAgente.PROIBIDO) else h
    }

    private val configs = Arb.list(hostsArb, 0..12).map { lista ->
        ConfigHosts(lista.distinctBy { it.id }.fold(emptyList<Host>()) { acc, h -> if (acc.any { it.nome.equals(h.nome, true) }) acc else acc + h })
    }

    @Test fun idaEVolta() = runBlocking<Unit> {
        checkAll(500, configs) { c -> assertEquals(c, CodecHosts.decodificar(CodecHosts.codificar(c))) }
    }
}
