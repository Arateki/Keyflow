package com.keyflow.computador.ssh

import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.ChaveDoServidor
import com.keyflow.computador.Host
import com.keyflow.computador.ImpressaoDigital
import com.keyflow.computador.RepositorioKnownHostsEmMemoria
import com.keyflow.computador.TesteSsh
import com.keyflow.computador.chaves.AssinadorSoftware
import com.keyflow.computador.chaves.ImportadorDeChave
import com.keyflow.computador.chaves.MaterialPrivado
import com.keyflow.computador.chaves.Resultado
import com.keyflow.computador.fixture.SshFixtureServer
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.transport.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conexão real do cbssh contra o `SshFixtureServer` (MINA em processo), na JVM. */
class ConexaoCbsshTest {
    private val sentinela = "SENTINELA-senha-8841"

    private class Interacao(var confirma: Boolean = true, var senha: String? = null) : InteracaoComUsuario {
        var confirmacoes = 0
        var pedidosDeSenha = 0
        var impressaoVista: ImpressaoDigital? = null
        val aguardando = CompletableDeferred<Unit>()
        var segurar: CompletableDeferred<Boolean>? = null
        override suspend fun confirmarServidor(host: String, porta: Int, algoritmo: String, impressao: ImpressaoDigital): Boolean {
            confirmacoes++; impressaoVista = impressao; aguardando.complete(Unit)
            return segurar?.await() ?: confirma
        }
        override suspend fun pedirSenha(): String? { pedidosDeSenha++; return senha }
        override suspend fun pedirCodigo(nome: String, instrucao: String, perguntas: List<String>, eco: List<Boolean>): List<String>? = null
    }

    private fun host(s: SshFixtureServer, usuario: String = "senha", auth: AutenticacaoHost = AutenticacaoHost.Senha) =
        Host("a1b2c3d4", "fixture", "127.0.0.1", s.porta, usuario, auth)

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun conexao(repo: RepositorioKnownHostsEmMemoria, i: Interacao, tempo: Long = 30_000, ajustar: (SshClientConfig.Builder) -> Unit = {}) =
        ConexaoCbssh(repo, i, escopo, tempoTrocaDeChavesMs = tempo, ajustar = ajustar)

    private val pty = PtyInfo(100, 30, 800, 480)

    private suspend fun lerAtePrompt(c: ConexaoSsh): ByteArray = withTimeout(15_000) {
        val saida = ByteArrayOutputStream()
        val fim = SshFixtureServer.PROMPT
        c.saida.first { saida.write(it); saida.toByteArray().let { b -> b.size >= fim.size && b.takeLast(fim.size).toByteArray().contentEquals(fim) } }
        saida.toByteArray()
    }

    private fun assinadorDe(pem: String): AssinadorSoftware {
        val imp = (ImportadorDeChave().importar(pem.toCharArray(), null) as Resultado.Ok).valor
        return AssinadorSoftware(imp.publica) { MaterialPrivado(imp.material.formato, imp.material.bytes.copyOf()) }
    }

    @Test fun conectaPorSenhaEServidorNovoPedeConfirmacao() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val i = Interacao()
            val repo = RepositorioKnownHostsEmMemoria()
            val c = conexao(repo, i)
            assertEquals(ResultadoConexao.Aberta, c.conectar(DestinoSsh(host(s), senhaLembrada = SshFixtureServer.SENHA_FALSA), pty))
            assertEquals(1, i.confirmacoes)
            assertEquals(s.hostSha256, i.impressaoVista!!.texto)
            assertTrue(String(lerAtePrompt(c)).endsWith("fixture$ "))
            assertEquals(SshFixtureServer.Pty("xterm-256color", 100, 30, 800, 480), s.ptys.poll(5, TimeUnit.SECONDS))
            assertEquals("COLORTERM" to "truecolor", s.ambientes.poll(5, TimeUnit.SECONDS))
            c.fechar()
            val c2 = conexao(repo, i)
            assertEquals(ResultadoConexao.Aberta, c2.conectar(DestinoSsh(host(s), SshFixtureServer.SENHA_FALSA), pty))
            assertEquals("conhecido: não pergunta de novo", 1, i.confirmacoes)
            c2.fechar()
        }
    }

    @Test fun conectaPorChaveEd25519() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val c = conexao(RepositorioKnownHostsEmMemoria(), Interacao())
            assertEquals(ResultadoConexao.Aberta, c.conectar(DestinoSsh(host(s, "teste", AutenticacaoHost.Chave("k1")), assinador = assinadorDe(s.chaveUsuarioPem)), pty))
            assertTrue(String(lerAtePrompt(c)).endsWith("fixture$ "))
            c.fechar()
        }
    }

    @Test fun chaveQueOServidorNaoConheceNaoEAceita() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val c = conexao(RepositorioKnownHostsEmMemoria(), Interacao())
            val d = DestinoSsh(host(s, "teste", AutenticacaoHost.Chave("k1")), assinador = assinadorDe(TesteSsh.chave("ed25519")))
            assertEquals(ResultadoConexao.Falhou(CausaSsh.CHAVE_NAO_ACEITA), c.conectar(d, pty))
        }
    }

    @Test fun senhaErradaEAutenticacao() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val c = conexao(RepositorioKnownHostsEmMemoria(), Interacao())
            val d = DestinoSsh(host(s), senhaLembrada = sentinela)
            assertEquals(ResultadoConexao.Falhou(CausaSsh.AUTENTICACAO), c.conectar(d, pty))
            assertFalse(d.toString().contains(sentinela))
            assertFalse(c.eventos.first().toString().contains(sentinela))
        }
    }

    @Test fun senhaPedidaAoUsuarioQuandoNaoLembrada() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val i = Interacao(senha = SshFixtureServer.SENHA_FALSA)
            val c = conexao(RepositorioKnownHostsEmMemoria(), i)
            assertEquals(ResultadoConexao.Aberta, c.conectar(DestinoSsh(host(s)), pty))
            assertEquals(1, i.pedidosDeSenha)
            c.fechar()
        }
    }

    @Test fun servidorDesligadoERecusada() = runBlocking(Dispatchers.IO) {
        val porta = ServerSocket(0).use { it.localPort }
        val h = Host("a1b2c3d4", "x", "127.0.0.1", porta, "u")
        assertEquals(ResultadoConexao.Falhou(CausaSsh.RECUSADA), conexao(RepositorioKnownHostsEmMemoria(), Interacao()).conectar(DestinoSsh(h), pty))
    }

    @Test fun hostInexistenteEDns() = runBlocking(Dispatchers.IO) {
        val h = Host("a1b2c3d4", "x", "nome-que-nao-existe.invalid", 22, "u")
        assertEquals(ResultadoConexao.Falhou(CausaSsh.DNS), conexao(RepositorioKnownHostsEmMemoria(), Interacao()).conectar(DestinoSsh(h), pty))
    }

    @Test fun nadaRespondeEmTempoComRelogioVirtual() = runTest {
        val c = ConexaoCbssh(RepositorioKnownHostsEmMemoria(), Interacao(), backgroundScope, tempoTcpMs = 15_000) { it.transportFactory = TransportFactory { awaitCancellation() } }
        val r = async { c.conectar(DestinoSsh(Host("a1b2c3d4", "x", "10.255.255.1", 22, "u")), pty) }
        delay(14_000)
        assertFalse(r.isCompleted)
        delay(2_000)
        assertEquals(ResultadoConexao.Falhou(CausaSsh.TEMPO), r.await())
    }

    @Test fun tcpTemQuinzeSegundosEATrocaDeChavesTrinta() = runTest {
        // TCP que conecta (transporte falso de leitura eterna) e depois silêncio na troca de chaves: estoura aos 15 + 30 s, não aos 15.
        val transporte = object : org.connectbot.sshlib.transport.Transport {
            override suspend fun read(count: Int): ByteArray = awaitCancellation()
            override suspend fun write(data: ByteArray) = Unit
            override suspend fun close() = Unit
        }
        val c = ConexaoCbssh(RepositorioKnownHostsEmMemoria(), Interacao(), backgroundScope) { it.transportFactory = TransportFactory { transporte } }
        val r = async { c.conectar(DestinoSsh(Host("a1b2c3d4", "x", "10.255.255.1", 22, "u")), pty) }
        delay(16_000)
        assertFalse("o TCP já conectou: 15 s não vale para a troca de chaves", r.isCompleted)
        delay(30_000)
        assertEquals(ResultadoConexao.Falhou(CausaSsh.TEMPO), r.await())
    }

    @Test fun cancelarNaConfirmacaoNaoTentaLogin() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val c = conexao(RepositorioKnownHostsEmMemoria(), Interacao(confirma = false))
            assertEquals(ResultadoConexao.Falhou(CausaSsh.SERVIDOR_NAO_CONFIADO), c.conectar(DestinoSsh(host(s), SshFixtureServer.SENHA_FALSA), pty))
            assertEquals(0, s.tentativasDeLogin.get())
        }
    }

    @Test fun servidorNovoSuspendeEOTempoNaoContaEnquantoOUsuarioDecide() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val i = Interacao().also { it.segurar = CompletableDeferred() }
            val c = conexao(RepositorioKnownHostsEmMemoria(), i, tempo = 300)
            val r = async { c.conectar(DestinoSsh(host(s), SshFixtureServer.SENHA_FALSA), pty) }
            i.aguardando.await()
            delay(1_000)
            assertFalse(r.isCompleted)
            assertEquals(0, s.tentativasDeLogin.get())
            i.segurar!!.complete(true)
            assertEquals(ResultadoConexao.Aberta, r.await())
            c.fechar()
        }
    }

    @Test fun chaveMudouBloqueiaSemChamarAutenticacao() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val repo = RepositorioKnownHostsEmMemoria()
            val primeira = conexao(repo, Interacao())
            primeira.conectar(DestinoSsh(host(s), SshFixtureServer.SENHA_FALSA), pty); primeira.fechar()
            val login0 = s.tentativasDeLogin.get()
            val real = repo.chavesConhecidas("127.0.0.1", s.porta).single()
            val adulterado = RepositorioKnownHostsEmMemoria().also { it.confiar("127.0.0.1", s.porta, ChaveDoServidor(real.algoritmo, real.blob.copyOf().also { b -> b[b.size - 1] = (b.last() + 1).toByte() })) }
            val i = Interacao(senha = "x")
            assertEquals(ResultadoConexao.Falhou(CausaSsh.CHAVE_DO_SERVIDOR_MUDOU), conexao(adulterado, i).conectar(DestinoSsh(host(s), SshFixtureServer.SENHA_FALSA), pty))
            assertEquals(login0, s.tentativasDeLogin.get())
            assertEquals(0, i.confirmacoes)
            assertEquals(0, i.pedidosDeSenha)
        }
    }

    @Test fun sessaoDoAgenteNuncaConfiaSozinha() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val i = Interacao()
            val d = DestinoSsh(host(s), SshFixtureServer.SENHA_FALSA, confiarSozinho = false)
            assertEquals(ResultadoConexao.Falhou(CausaSsh.SERVIDOR_PEDE_CONFIRMACAO), conexao(RepositorioKnownHostsEmMemoria(), i).conectar(d, pty))
            assertEquals(0, i.confirmacoes)
            assertEquals(0, s.tentativasDeLogin.get())
        }
    }

    @Test fun redimensionarChegaComoWindowChange() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val c = conexao(RepositorioKnownHostsEmMemoria(), Interacao())
            c.conectar(DestinoSsh(host(s), SshFixtureServer.SENHA_FALSA), pty)
            c.redimensionar(PtyInfo(120, 40, 960, 640))
            assertEquals(SshFixtureServer.Tamanho(120, 40, 960, 640), s.tamanhos.poll(5, TimeUnit.SECONDS))
            c.fechar()
        }
    }

    @Test fun testarNaoPedeOShell() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val r = conexao(RepositorioKnownHostsEmMemoria(), Interacao()).testar(DestinoSsh(host(s), SshFixtureServer.SENHA_FALSA)) as ResultadoTeste.Ok
            assertEquals("password", r.metodo)
            assertEquals(s.hostSha256, r.impressao.texto)
            assertTrue(r.algoritmoDoServidor.isNotEmpty() && r.latenciaMs >= 0)
            assertEquals(null, s.ptys.poll(500, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun servidorQueCaiNoMeioViraDesconectado() = runBlocking(Dispatchers.IO) {
        SshFixtureServer(cairNoMeio = true).use { s ->
            val c = conexao(RepositorioKnownHostsEmMemoria(), Interacao())
            assertEquals(ResultadoConexao.Aberta, c.conectar(DestinoSsh(host(s), SshFixtureServer.SENHA_FALSA), pty))
            withTimeout(15_000) { c.eventos.first { it is EventoConexao.Caiu } }
            c.fechar()
        }
    }

    @Test fun cancelarACoroutineFechaOSocket() = runBlocking(Dispatchers.IO) {
        SshFixtureServer().use { s ->
            val i = Interacao().also { it.segurar = CompletableDeferred() }
            val r = async { conexao(RepositorioKnownHostsEmMemoria(), i).conectar(DestinoSsh(host(s), SshFixtureServer.SENHA_FALSA), pty) }
            i.aguardando.await()
            assertTrue(s.sessoesAbertas >= 1)
            r.cancel()
            withTimeout(10_000) { while (s.sessoesAbertas > 0) delay(50) }
            assertEquals(0, s.sessoesAbertas)
        }
    }
}
