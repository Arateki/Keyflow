package com.keyflow.computador

import com.keyflow.computador.chaves.ChaveSsh
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GravadorDeHostsTest {
    private class Dados : DadosDaEdicaoDeHost {
        var config = ConfigHosts()
        val segredos = mutableMapOf<String, String>()
        val eventos = mutableListOf<String>()
        var falhaNoCofre = false
        var ignoraCofre = false
        var falhaNoConfig = false
        override suspend fun hostsOnce() = config
        override suspend fun chavesOnce() = emptyList<ChaveSsh>()
        override fun senhaLembrada(hostId: String) = segredos[hostId]
        override fun lembrarSenha(hostId: String, senha: String) {
            eventos += "senha"
            if (falhaNoCofre) throw IOException("cofre falso indisponível")
            if (!ignoraCofre) segredos[hostId] = senha
        }
        override fun esquecerSenha(hostId: String) { eventos += "apagar"; segredos.remove(hostId) }
        override suspend fun atualizarHosts(f: (ConfigHosts) -> ConfigHosts): ConfigHosts {
            if (falhaNoConfig) throw IOException("DataStore falso indisponível")
            config = f(config)
            eventos += "config"
            for (h in config.hosts) if (h.agentePodeUsar && h.autenticacao == AutenticacaoHost.Senha) {
                assertTrue("a marca não pode ser publicada antes do segredo", !senhaLembrada(h.id).isNullOrEmpty())
            }
            return config
        }
    }
    private fun h(auth: AutenticacaoHost = AutenticacaoHost.Senha, lembrar: Boolean = true) =
        Host("aaaaaaaa", "dev", "dev.example.org", 22, "yan", auth, lembrar, PoliticaAgente.PERMITIDO)

    @Test fun lembrarSemSenhaNaoGravaHostNemPermiteAgente() = runTest {
        for (senha in listOf(null, "")) {
            val d = Dados()
            val r = GravadorDeHosts(d).salvar(h(), senha) as ResultadoEdicao.Erros
            assertTrue(r.erros.any { it.motivo == MotivoHost.SENHA_NAO_INFORMADA })
            assertTrue(d.config.hosts.isEmpty()); assertTrue(d.eventos.isEmpty())
        }
    }
    @Test fun segredoEConfirmadoAntesDaPublicacaoDaMarca() = runTest {
        val d = Dados()
        val r = GravadorDeHosts(d).salvar(h(), "senha-falsa") as ResultadoEdicao.Ok
        assertEquals(listOf("senha", "config"), d.eventos)
        assertTrue(r.host.agentePodeUsar); assertEquals("senha-falsa", d.segredos[r.host.id])
    }
    @Test fun edicaoSemNovaSenhaPreservaOSegredoAnterior() = runTest {
        val d = Dados(); d.segredos["aaaaaaaa"] = "senha-falsa"
        val r = GravadorDeHosts(d).salvar(h(), null) as ResultadoEdicao.Ok
        assertTrue(r.host.agentePodeUsar); assertEquals(listOf("config"), d.eventos)
        assertEquals("senha-falsa", d.segredos[r.host.id])
    }
    @Test fun cofreQueFalhaOuIgnoraAGravacaoNaoPublicaPermissao() = runTest {
        for (ignora in listOf(false, true)) {
            val d = Dados(); d.falhaNoCofre = !ignora; d.ignoraCofre = ignora
            val r = GravadorDeHosts(d).salvar(h(), "senha-falsa") as ResultadoEdicao.Erros
            assertEquals(MotivoHost.SENHA_NAO_GUARDADA, r.erros.single().motivo)
            assertTrue(d.config.hosts.isEmpty()); assertFalse(d.eventos.contains("config"))
        }
    }
    @Test fun erroNosCamposNaoTocaNoCofre() = runTest {
        val d = Dados()
        assertTrue(GravadorDeHosts(d).salvar(h().copy(porta = 0), "senha-falsa") is ResultadoEdicao.Erros)
        assertTrue(d.eventos.isEmpty())
    }
    @Test fun erroNoDataStoreRestauraSenhaAnterior() = runTest {
        val d = Dados(); d.segredos["aaaaaaaa"] = "anterior-falsa"; d.falhaNoConfig = true
        try { GravadorDeHosts(d).salvar(h(), "nova-falsa"); throw AssertionError("deveria falhar") } catch (_: IOException) { }
        assertEquals("anterior-falsa", d.segredos["aaaaaaaa"]); assertTrue(d.config.hosts.isEmpty())
    }
    @Test fun chaveVaziaOuQueNaoExisteNaoPodeSerGravada() = runTest {
        for (id in listOf("", "  ", "inexistente")) {
            val d = Dados()
            val r = GravadorDeHosts(d).salvar(h(AutenticacaoHost.Chave(id), false), null) as ResultadoEdicao.Erros
            assertEquals(MotivoHost.CHAVE_NAO_GUARDADA, r.erros.single().motivo)
            assertTrue(d.config.hosts.isEmpty())
        }
    }
    @Test fun esquecerSenhaPrimeiroPublicaHostSemPermissaoEDepoisApaga() = runTest {
        val d = Dados(); d.config = ConfigHosts(listOf(h())); d.segredos["aaaaaaaa"] = "senha-falsa"
        val r = GravadorDeHosts(d).salvar(h(lembrar = false), null) as ResultadoEdicao.Ok
        assertFalse(r.host.agentePodeUsar); assertFalse(r.host.lembrarSenha)
        assertEquals(listOf("config", "apagar"), d.eventos); assertTrue(d.segredos.isEmpty())
    }
    @Test fun cancelarEnquantoODataStoreConfirmaNaoDeixaPermissaoSemSenha() = runTest {
        val d = Dados()
        val iniciou = CompletableDeferred<Unit>()
        val liberar = CompletableDeferred<Unit>()
        val porta = object : DadosDaEdicaoDeHost by d {
            override suspend fun atualizarHosts(f: (ConfigHosts) -> ConfigHosts): ConfigHosts {
                // Como um ator persistente: pode confirmar antes de o chamador receber a resposta cancelada.
                withContext(NonCancellable) {
                    iniciou.complete(Unit); liberar.await(); d.atualizarHosts(f)
                }
                currentCoroutineContext().ensureActive()
                return d.config
            }
        }
        val job = launch { GravadorDeHosts(porta).salvar(h(), "senha-falsa") }
        iniciou.await(); job.cancel(); liberar.complete(Unit); job.join()
        assertTrue(d.config.hosts.single().agentePodeUsar)
        assertEquals("senha-falsa", d.segredos["aaaaaaaa"])
    }
}
