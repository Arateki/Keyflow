package com.keyflow.computador.sessao

import com.keyflow.computador.Host
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
class GerenciadorDeSessoesTest {
    private fun TestScope.gerenciador(dep: DependenciasFalsas) = GerenciadorDeSessoes(backgroundScope, dep)

    private fun h(n: Int) = Host("%08x".format(n), "h$n", "x.org", 22, "u")

    private fun aberta(r: ResultadoAbertura) = (r as ResultadoAbertura.Aberta).sessao

    @Test fun nonaSessaoERecusadaComOMotivo() = runTest {
        val g = gerenciador(DependenciasFalsas { currentTime })
        repeat(8) { aberta(g.abrir(h(it), DonoDaSessao.USUARIO)) }
        assertEquals(ResultadoAbertura.Recusada(MotivoRecusa.LIMITE_TOTAL), g.abrir(h(9), DonoDaSessao.USUARIO))
        assertTrue(MotivoRecusa.LIMITE_TOTAL.frase.contains("8"))
    }

    @Test fun quartaSessaoDoAgenteERecusadaMasDoUsuarioNao() = runTest {
        val g = gerenciador(DependenciasFalsas { currentTime })
        repeat(3) { aberta(g.abrir(h(it), DonoDaSessao.AGENTE)) }
        assertEquals(ResultadoAbertura.Recusada(MotivoRecusa.LIMITE_DO_AGENTE), g.abrir(h(4), DonoDaSessao.AGENTE))
        aberta(g.abrir(h(5), DonoDaSessao.USUARIO))
    }

    @Test fun agenteOciosoFechaEUsuarioNao() = runTest {
        val dep = DependenciasFalsas { currentTime }
        val g = gerenciador(dep)
        val a = aberta(g.abrir(h(1), DonoDaSessao.AGENTE))
        val u = aberta(g.abrir(h(2), DonoDaSessao.USUARIO))
        runCurrent()
        advanceTimeBy(9 * 60_000L); runCurrent()
        assertEquals(EstadoSessao.Aberta, a.estado.value)
        advanceTimeBy(2 * 60_000L); runCurrent()
        assertEquals(EstadoSessao.Encerrada(MotivoEncerramento.OCIOSIDADE), a.estado.value)
        advanceTimeBy(3_600_000L); runCurrent()
        assertEquals(EstadoSessao.Aberta, u.estado.value)
    }

    @Test fun atividadeAdiaAOciosidadeDoAgente() = runTest {
        val dep = DependenciasFalsas { currentTime }
        val g = gerenciador(dep)
        val a = aberta(g.abrir(h(1), DonoDaSessao.AGENTE))
        runCurrent()
        advanceTimeBy(9 * 60_000L); runCurrent()
        a.escrever("ls".toByteArray())
        advanceTimeBy(9 * 60_000L); runCurrent()
        assertEquals(EstadoSessao.Aberta, a.estado.value)
    }

    @Test fun primeiraEUltimaSessaoEmitemEventos() = runTest {
        val dep = DependenciasFalsas { currentTime }
        val g = gerenciador(dep)
        val vistos = mutableListOf<EventoGerenciador>()
        backgroundScope.launch { g.eventos.collect { vistos += it } }
        runCurrent()
        val a = aberta(g.abrir(h(1), DonoDaSessao.USUARIO)); runCurrent()
        val b = aberta(g.abrir(h(2), DonoDaSessao.USUARIO)); runCurrent()
        assertEquals(listOf(EventoGerenciador.PrimeiraSessao), vistos)
        g.fechar(a.id); runCurrent()
        assertEquals(listOf(EventoGerenciador.PrimeiraSessao), vistos)
        g.fechar(b.id); runCurrent()
        assertEquals(listOf(EventoGerenciador.PrimeiraSessao, EventoGerenciador.SemSessoes), vistos)
        assertTrue(g.resumos.value.isEmpty())
    }

    @Test fun fecharDoHostEDoAgenteSoAfetamOQuePediram() = runTest {
        val g = gerenciador(DependenciasFalsas { currentTime })
        val a = aberta(g.abrir(h(1), DonoDaSessao.USUARIO))
        val b = aberta(g.abrir(h(1), DonoDaSessao.AGENTE))
        val c = aberta(g.abrir(h(2), DonoDaSessao.AGENTE))
        runCurrent()
        g.fecharDoAgente(); runCurrent()
        assertEquals(EstadoSessao.Aberta, a.estado.value)
        assertEquals(EstadoSessao.Encerrada(MotivoEncerramento.AGENTE_PARADO), b.estado.value)
        assertEquals(EstadoSessao.Encerrada(MotivoEncerramento.AGENTE_PARADO), c.estado.value)
        g.fecharDoHost(h(1).id); runCurrent()
        assertEquals(EstadoSessao.Encerrada(MotivoEncerramento.HOST_REMOVIDO), a.estado.value)
        assertTrue(g.resumos.value.isEmpty())
    }

    @Test fun compartilharMostraAoAgenteSoAQueOUsuarioLiberou() = runTest {
        val g = gerenciador(DependenciasFalsas { currentTime })
        val u1 = aberta(g.abrir(h(1), DonoDaSessao.USUARIO))
        aberta(g.abrir(h(2), DonoDaSessao.USUARIO))
        val ag = aberta(g.abrir(h(3), DonoDaSessao.AGENTE))
        assertEquals(listOf(ag.id), g.sessoesVisiveisAoAgente().map { it.id })
        g.compartilhar(u1.id, true)
        assertEquals(setOf(ag.id, u1.id), g.sessoesVisiveisAoAgente().map { it.id }.toSet())
        assertTrue(g.resumos.value.first { it.id == u1.id }.compartilhada)
    }

    @Test fun resumoNaoCarregaHostNemTexto() {
        val campos = SessaoResumo::class.java.declaredFields.map { it.name }
        assertTrue(campos.toString(), campos.none { it in setOf("host", "usuario", "texto", "endereco", "buffer") })
    }

    @Test fun sessaoQueFalhouContinuaNaListaAteFecharENaoContaComoViva() = runTest {
        val dep = DependenciasFalsas { currentTime }
        dep.fila += ConexaoFalsa(com.keyflow.computador.ssh.ResultadoConexao.Falhou(com.keyflow.computador.ssh.CausaSsh.RECUSADA))
        val g = gerenciador(dep)
        val vistos = mutableListOf<EventoGerenciador>()
        backgroundScope.launch { g.eventos.collect { vistos += it } }
        runCurrent()
        val s = aberta(g.abrir(h(1), DonoDaSessao.USUARIO)); runCurrent()
        assertTrue(s.estado.value is EstadoSessao.Falhou)
        assertEquals(1, g.resumos.value.size)
        assertEquals(listOf(EventoGerenciador.PrimeiraSessao, EventoGerenciador.SemSessoes), vistos)
    }
}
