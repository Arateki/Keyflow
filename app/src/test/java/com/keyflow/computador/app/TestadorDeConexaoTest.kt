package com.keyflow.computador.app

import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.ChaveDoServidor
import com.keyflow.computador.Host
import com.keyflow.computador.RepositorioDeConfianca
import com.keyflow.computador.fixture.SshFixtureServer
import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.computador.ssh.ResultadoTeste
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CMP-08 contra o `SshFixtureServer` de verdade: o teste é real, mostra impressão e latência e nunca passa a confiar no servidor. */
class TestadorDeConexaoTest {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class ConfiancaMem : RepositorioDeConfianca {
        val itens = mutableListOf<Triple<String, Int, ChaveDoServidor>>()
        override fun chavesConhecidas(host: String, porta: Int) = itens.filter { it.first == host && it.second == porta }.map { it.third }
        override fun confiar(host: String, porta: Int, chave: ChaveDoServidor) { itens += Triple(host, porta, chave) }
        override fun esquecer(host: String, porta: Int) { itens.removeAll { it.first == host && it.second == porta } }
    }

    private fun host(porta: Int, usuario: String = "senha", auth: AutenticacaoHost = AutenticacaoHost.Senha) = Host("a1b2c3d4", "fixture", "127.0.0.1", porta, usuario, auth)

    @Test fun mostraImpressaoMetodoELatenciaENaoPassaAConfiarNoServidor() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val base = ConfiancaMem()
            val r = TestadorDeConexao(base, escopo).testar(host(s.porta), SshFixtureServer.SENHA_FALSA, null)
            r as ResultadoTeste.Ok
            assertEquals(s.hostSha256, r.impressao.texto)
            assertEquals("password", r.metodo)
            assertTrue(r.latenciaMs >= 0)
            assertTrue("o teste não grava no known_hosts", base.itens.isEmpty())
            // E uma segunda vez também pergunta de novo (nada ficou).
            assertTrue(TestadorDeConexao(base, escopo).testar(host(s.porta), SshFixtureServer.SENHA_FALSA, null) is ResultadoTeste.Ok)
        }
    }

    @Test fun senhaErradaEAutenticacaoESemSenhaTambem() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val t = TestadorDeConexao(ConfiancaMem(), escopo)
            assertEquals(ResultadoTeste.Falhou(CausaSsh.AUTENTICACAO), t.testar(host(s.porta), "errada", null))
            assertEquals(ResultadoTeste.Falhou(CausaSsh.AUTENTICACAO), t.testar(host(s.porta, auth = AutenticacaoHost.Perguntar), null, null))
        }
    }

    @Test fun portaFechadaDizOServidorRecusouEnuncaAFraseFixaAntiga() = runBlocking(Dispatchers.IO) {
        val livre = ServerSocket(0).use { it.localPort }
        val r = TestadorDeConexao(ConfiancaMem(), escopo).testar(host(livre), "x", null)
        assertEquals(ResultadoTeste.Falhou(CausaSsh.RECUSADA), r)
        assertEquals("O servidor recusou a conexão.", CausaSsh.RECUSADA.frase)
    }

    @Test fun servidorConhecidoComChaveDiferenteContinuaBloqueado() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val base = ConfiancaMem()
            base.confiar("127.0.0.1", s.porta, ChaveDoServidor("ecdsa-sha2-nistp256", ByteArray(8) { 1 }))
            val r = TestadorDeConexao(base, escopo).testar(host(s.porta), SshFixtureServer.SENHA_FALSA, null)
            assertEquals(ResultadoTeste.Falhou(CausaSsh.CHAVE_DO_SERVIDOR_MUDOU), r)
        }
    }

    @Test fun tempoMaximoVira15SegundosDeTempo() = runBlocking(Dispatchers.IO) {
        val parado = com.keyflow.computador.ssh.ResultadoTeste.Falhou(CausaSsh.TEMPO)
        val fabrica = { _: RepositorioDeConfianca, _: com.keyflow.computador.ssh.InteracaoComUsuario, _: CoroutineScope ->
            object : com.keyflow.computador.ssh.ConexaoSsh {
                override val eventos = kotlinx.coroutines.flow.MutableSharedFlow<com.keyflow.computador.ssh.EventoConexao>()
                override val saida = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
                override suspend fun conectar(destino: com.keyflow.computador.ssh.DestinoSsh, pty: com.keyflow.computador.ssh.PtyInfo): com.keyflow.computador.ssh.ResultadoConexao = error("não")
                override suspend fun testar(destino: com.keyflow.computador.ssh.DestinoSsh): ResultadoTeste = kotlinx.coroutines.awaitCancellation()
                override suspend fun escrever(bytes: ByteArray) = Unit
                override suspend fun redimensionar(pty: com.keyflow.computador.ssh.PtyInfo) = Unit
                override suspend fun fechar() = Unit
            }
        }
        assertEquals(parado, TestadorDeConexao(ConfiancaMem(), escopo, limiteMs = 200, fabrica = fabrica).testar(host(1), null, null))
    }
}
