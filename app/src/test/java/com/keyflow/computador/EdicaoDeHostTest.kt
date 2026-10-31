package com.keyflow.computador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdicaoDeHostTest {
    private fun host(id: String = "aaaaaaaa", nome: String = "dev", auth: AutenticacaoHost = AutenticacaoHost.Senha, lembrar: Boolean = true, agente: PoliticaAgente = PoliticaAgente.PROIBIDO, porta: Int = 22) =
        Host(id, nome, "dev.example.org", porta, "yan", auth, lembrar, agente)

    private fun aplicar(c: ConfigHosts, h: Host, passphrase: (String) -> Boolean = { false }) =
        EdicaoDeHost.aplicar(c, h, credencialDisponivel = true, chavePedePassphrase = passphrase)

    @Test fun hostValidoEAcrescentadoComEspacosAparados() {
        val r = aplicar(ConfigHosts(), host(nome = "  dev  ")) as ResultadoEdicao.Ok
        assertEquals("dev", r.host.nome)
        assertEquals(1, r.config.hosts.size)
    }

    @Test fun porta0ENomeRepetidoVoltamOMotivo() {
        val base = ConfigHosts(listOf(host(id = "11111111", nome = "dev")))
        val r = aplicar(base, host(id = "22222222", nome = "DEV", porta = 0)) as ResultadoEdicao.Erros
        assertTrue(r.erros.any { it.campo == CampoHost.PORTA && it.motivo == MotivoHost.PORTA_INVALIDA })
        assertTrue(r.erros.any { it.campo == CampoHost.NOME && it.motivo == MotivoHost.NOME_REPETIDO })
    }

    @Test fun editarOProprioHostNaoColideComOProprioNome() {
        val base = ConfigHosts(listOf(host(nome = "dev")))
        val r = aplicar(base, host(nome = "dev").copy(usuario = "outro")) as ResultadoEdicao.Ok
        assertEquals(1, r.config.hosts.size)
        assertEquals("outro", r.config.hosts.single().usuario)
    }

    @Test fun agenteNuncaFicaLigadoEmHostQueOAppNaoAutenticaSozinho() {
        val perguntar = aplicar(ConfigHosts(), host(auth = AutenticacaoHost.Perguntar, lembrar = false, agente = PoliticaAgente.PERMITIDO)) as ResultadoEdicao.Ok
        assertEquals(PoliticaAgente.PROIBIDO, perguntar.host.agente)
        val semSenha = aplicar(ConfigHosts(), host(auth = AutenticacaoHost.Senha, lembrar = false, agente = PoliticaAgente.PERMITIDO)) as ResultadoEdicao.Ok
        assertEquals(PoliticaAgente.PROIBIDO, semSenha.host.agente)
        val ok = aplicar(ConfigHosts(), host(auth = AutenticacaoHost.Senha, lembrar = true, agente = PoliticaAgente.PERMITIDO)) as ResultadoEdicao.Ok
        assertEquals(PoliticaAgente.PERMITIDO, ok.host.agente)
        val chavePassphrase = aplicar(ConfigHosts(), host(auth = AutenticacaoHost.Chave("k1"), lembrar = false, agente = PoliticaAgente.PERMITIDO)) { true } as ResultadoEdicao.Ok
        assertEquals(PoliticaAgente.PROIBIDO, chavePassphrase.host.agente)
    }

    @Test fun lembrarSenhaSoVaiComAutenticacaoPorSenha() {
        val r = aplicar(ConfigHosts(), host(auth = AutenticacaoHost.Chave("k"), lembrar = true)) as ResultadoEdicao.Ok
        assertEquals(false, r.host.lembrarSenha)
    }

    @Test fun cinquentaHostsEOLimite() {
        val cheio = ConfigHosts((1..50).map { host(id = "%08x".format(it), nome = "h$it") })
        val r = aplicar(cheio, host(id = "ffffffff", nome = "novo")) as ResultadoEdicao.Erros
        assertTrue(r.erros.any { it.motivo == MotivoHost.LISTA_CHEIA })
    }

    @Test fun idNovoNaoColide() {
        val ids = ArrayDeque(listOf("aaaaaaaa", "aaaaaaaa", "bbbbbbbb"))
        assertEquals("bbbbbbbb", EdicaoDeHost.novoId(setOf("aaaaaaaa")) { ids.removeFirst() })
    }
}
