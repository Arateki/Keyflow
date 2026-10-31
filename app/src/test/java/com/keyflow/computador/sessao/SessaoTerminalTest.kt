package com.keyflow.computador.sessao

import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.computador.ssh.EventoConexao
import com.keyflow.computador.ssh.PtyInfo
import com.keyflow.computador.ssh.ResultadoConexao
import com.keyflow.computador.ImpressaoDigital
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessaoTerminalTest {
    private class Tela : PortaDoEmulador {
        val recebido = java.io.ByteArrayOutputStream()
        override fun escreverNaTela(bytes: ByteArray) { recebido.write(bytes) }
        override fun tamanhoAtual() = PtyInfo(80, 24)
    }

    private fun TestScope.nova(dep: DependenciasFalsas, tela: Tela? = null) =
        SessaoTerminal("s1", HOST, DonoDaSessao.USUARIO, dep, backgroundScope, porta = tela)

    /** `advanceUntilIdle` ignora tarefas do `backgroundScope`; a sessão vive nele, então avança o relógio por cima. */
    private suspend fun TestScope.avancarTudo() { runCurrent(); advanceTimeBy(3_600_000); runCurrent() }

    @Test fun fluxoNormalPassaPorAutenticandoEAbertaERecebeSaida() = runTest {
        val dep = DependenciasFalsas { currentTime }
        val conn = ConexaoFalsa(eventosNaConexao = listOf(EventoConexao.Autenticando)).also { dep.fila += it }
        val tela = Tela()
        val s = nova(dep, tela)
        val vistos = mutableListOf<EstadoSessao>()
        backgroundScope.launch { s.estado.collect { vistos += it } }
        s.iniciar(PtyInfo(80, 24))
        runCurrent()
        assertEquals(EstadoSessao.Aberta, s.estado.value)
        assertTrue(EstadoSessao.Autenticando in vistos || vistos.contains(EstadoSessao.Aberta))
        conn.canal.send("olá\n".toByteArray())
        runCurrent()
        assertEquals("olá", s.buffer.ultimasLinhas(1).single())
        assertEquals("olá\n", String(tela.recebido.toByteArray()))
        s.escrever("ls".toByteArray())
        assertEquals("ls", String(conn.escritos.single()))
        assertTrue(s.transicoesImpossiveis.isEmpty())
    }

    @Test fun quedaNoMeioReconectaEPoeASeparadoraNoBufferENaTela() = runTest {
        val dep = DependenciasFalsas { currentTime }
        val primeira = ConexaoFalsa().also { dep.fila += it }
        val segunda = ConexaoFalsa().also { dep.fila += it }
        val tela = Tela()
        val s = nova(dep, tela)
        s.iniciar(PtyInfo(80, 24))
        runCurrent()
        primeira.canal.send("antes\n".toByteArray())
        runCurrent()
        primeira.cair(CausaSsh.REDE)
        runCurrent()
        assertEquals(EstadoSessao.Reconectando(1), s.estado.value)
        advanceTimeBy(999); runCurrent()
        assertEquals(EstadoSessao.Reconectando(1), s.estado.value)
        advanceTimeBy(2); runCurrent()
        assertEquals(EstadoSessao.Aberta, s.estado.value)
        assertEquals(2, dep.destinos)
        assertEquals(listOf("antes", "", "— conexão reiniciada —"), s.buffer.ultimasLinhas(5))
        assertTrue(String(tela.recebido.toByteArray()).contains("— conexão reiniciada —"))
        segunda.canal.send("depois\n".toByteArray()); runCurrent()
        assertEquals("depois", s.buffer.ultimasLinhas(1).single())
        assertTrue(s.transicoesImpossiveis.isEmpty())
    }

    @Test fun dezTentativasFalhasViramFalhouEAOnzenaNaoExiste() = runTest {
        val dep = DependenciasFalsas { currentTime }
        dep.fila += ConexaoFalsa()
        repeat(10) { dep.fila += ConexaoFalsa(ResultadoConexao.Falhou(CausaSsh.REDE)) }
        val s = nova(dep)
        s.iniciar(PtyInfo(80, 24))
        runCurrent()
        dep.criadas.first().cair(CausaSsh.REDE)
        avancarTudo()
        assertEquals(EstadoSessao.Falhou(CausaSsh.REDE), s.estado.value)
        assertEquals("1 inicial + 10 tentativas", 11, dep.criadas.size)
        assertTrue(s.transicoesImpossiveis.isEmpty())
    }

    @Test fun primeiraConexaoQueFalhaNaoSeRepete() = runTest {
        val dep = DependenciasFalsas { currentTime }
        dep.fila += ConexaoFalsa(ResultadoConexao.Falhou(CausaSsh.RECUSADA))
        val s = nova(dep)
        s.iniciar(PtyInfo(80, 24)); avancarTudo()
        assertEquals(EstadoSessao.Falhou(CausaSsh.RECUSADA), s.estado.value)
        assertEquals(1, dep.criadas.size)
    }

    @Test fun autenticacaoRecusadaNaReconexaoNaoTentaDeNovo() = runTest {
        val dep = DependenciasFalsas { currentTime }
        dep.fila += ConexaoFalsa(); dep.fila += ConexaoFalsa(ResultadoConexao.Falhou(CausaSsh.AUTENTICACAO))
        val s = nova(dep)
        s.iniciar(PtyInfo(80, 24)); runCurrent()
        dep.criadas.first().cair(); avancarTudo()
        assertEquals(EstadoSessao.Falhou(CausaSsh.AUTENTICACAO), s.estado.value)
        assertEquals(2, dep.criadas.size)
    }

    @Test fun biometriaForaDaJanelaNaoReconectaSozinhaEDentroDaJanelaReconecta() = runTest {
        val dep = DependenciasFalsas { currentTime }
        dep.fila += ConexaoFalsa(); dep.fila += ConexaoFalsa()
        val s = nova(dep)
        s.iniciar(PtyInfo(80, 24)); runCurrent()
        dep.biometriaForaDaJanela = true
        dep.criadas.first().cair(); advanceTimeBy(3_600_000); runCurrent()
        assertEquals(EstadoSessao.PrecisaDoUsuario, s.estado.value)
        assertEquals("nenhuma tentativa automática", 1, dep.criadas.size)
        // O usuário tocou em "reconectar": a janela foi aberta pelo prompt dele.
        dep.biometriaForaDaJanela = false
        s.retomar(); avancarTudo()
        assertEquals(EstadoSessao.Aberta, s.estado.value)
        assertEquals(2, dep.criadas.size)
        assertTrue(s.transicoesImpossiveis.isEmpty())
    }

    @Test fun dentroDaJanelaReconectaSemPrompt() = runTest {
        val dep = DependenciasFalsas { currentTime }
        dep.fila += ConexaoFalsa(); dep.fila += ConexaoFalsa()
        val s = nova(dep)
        s.iniciar(PtyInfo(80, 24)); runCurrent()
        dep.biometriaForaDaJanela = false
        dep.criadas.first().cair(); advanceTimeBy(1_500); runCurrent()
        assertEquals(EstadoSessao.Aberta, s.estado.value)
    }

    @Test fun semRedeEsperaARedeVoltar() = runTest {
        val dep = DependenciasFalsas { currentTime }
        dep.fila += ConexaoFalsa(); dep.fila += ConexaoFalsa()
        val s = nova(dep)
        s.iniciar(PtyInfo(80, 24)); runCurrent()
        dep.rede.value = false
        dep.criadas.first().cair(); advanceTimeBy(600_000); runCurrent()
        assertEquals(EstadoSessao.Reconectando(1), s.estado.value)
        assertEquals(1, dep.criadas.size)
        dep.rede.value = true
        advanceTimeBy(1_100); runCurrent()
        assertEquals(EstadoSessao.Aberta, s.estado.value)
    }

    @Test fun fecharEncerraACexaoEMarcaOMotivo() = runTest {
        val dep = DependenciasFalsas { currentTime }
        val conn = ConexaoFalsa().also { dep.fila += it }
        val s = nova(dep)
        s.iniciar(PtyInfo(80, 24)); runCurrent()
        s.fechar()
        runCurrent()
        assertEquals(EstadoSessao.Encerrada(MotivoEncerramento.USUARIO), s.estado.value)
        assertTrue(conn.fechou)
        assertTrue(s.transicoesImpossiveis.isEmpty())
    }

    @Test fun sequenciaAleatoriaNuncaViolaAMaquina() = runTest {
        val r = java.util.Random(99)
        repeat(60) { rodada ->
            val dep = DependenciasFalsas { currentTime }
            repeat(14) {
                dep.fila += when (r.nextInt(4)) {
                    0 -> ConexaoFalsa(ResultadoConexao.Falhou(CausaSsh.entries[r.nextInt(CausaSsh.entries.size)]))
                    1 -> ConexaoFalsa(eventosNaConexao = listOf(EventoConexao.AguardandoServidor("ssh-ed25519", ImpressaoDigital.de(byteArrayOf(1))), EventoConexao.Autenticando))
                    else -> ConexaoFalsa()
                }
            }
            val s = nova(dep)
            s.iniciar(PtyInfo(80, 24))
            repeat(6) {
                runCurrent()
                when (r.nextInt(4)) {
                    0 -> dep.criadas.lastOrNull()?.cair(CausaSsh.entries[r.nextInt(CausaSsh.entries.size)])
                    1 -> dep.rede.value = r.nextBoolean()
                    2 -> dep.biometriaForaDaJanela = r.nextBoolean()
                    else -> if (s.estado.value == EstadoSessao.PrecisaDoUsuario) { dep.biometriaForaDaJanela = false; s.retomar() }
                }
                advanceTimeBy(r.nextLong(0, 40_000))
            }
            if (r.nextBoolean()) s.fechar()
            avancarTudo()
            assertTrue("rodada $rodada: ${s.transicoesImpossiveis}", s.transicoesImpossiveis.isEmpty())
        }
    }
}
