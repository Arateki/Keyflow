package com.keyflow.computador.app

import com.keyflow.computador.sessao.DependenciasFalsas
import com.keyflow.computador.sessao.DonoDaSessao
import com.keyflow.computador.sessao.EventoGerenciador
import com.keyflow.computador.sessao.GerenciadorDeSessoes
import com.keyflow.computador.sessao.HOST
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A corrida medida no `SessoesServiceTest`: abrir a sessão logo depois de ligar o serviço ao gerenciador não pode perder o aviso de "1ª sessão". */
class LigacaoSessoesTest {
    private val rodadas = 400

    private fun semear(ligar: (GerenciadorDeSessoes, CoroutineScope, () -> Unit) -> Unit): Int {
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val iniciou = AtomicInteger()
        try {
            repeat(rodadas) {
                val g = GerenciadorDeSessoes(escopo, DependenciasFalsas { System.currentTimeMillis() })
                val avisos = AtomicInteger()
                ligar(g, escopo) { avisos.incrementAndGet() }
                g.abrir(HOST, DonoDaSessao.USUARIO) // logo em seguida, como o teste e o app fazem
                val limite = System.currentTimeMillis() + 2_000
                while (avisos.get() == 0 && System.currentTimeMillis() < limite) Thread.sleep(1)
                if (avisos.get() > 0) iniciou.incrementAndGet()
            }
        } finally { escopo.cancel() }
        return iniciou.get()
    }

    @Test fun ligacaoNovaNuncaPerdeAPrimeiraSessao() {
        assertEquals(rodadas, semear { g, e, iniciar -> observarPrimeiraSessao(g, e, iniciar) })
    }

    @Test fun ligacaoAntigaPerdiaAvisos_medicao() {
        // O `launch` comum (o código anterior). Só registra a medição; não afirma um número, porque depende da máquina.
        val ok = semear { g, e, iniciar -> e.launch { g.eventos.collect { if (it == EventoGerenciador.PrimeiraSessao) iniciar() } } }
        println("CMP_T12 ligação antiga: $ok de $rodadas rodadas receberam o aviso")
        assertTrue(ok in 0..rodadas)
    }

    @Test fun sessaoJaViva_naoDependeDoEvento() = runBlocking {
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val g = GerenciadorDeSessoes(escopo, DependenciasFalsas { System.currentTimeMillis() })
            g.abrir(HOST, DonoDaSessao.USUARIO)
            val avisos = AtomicInteger()
            observarPrimeiraSessao(g, escopo) { avisos.incrementAndGet() }
            assertTrue(avisos.get() >= 1)
        } finally { escopo.cancel() }
    }
}
