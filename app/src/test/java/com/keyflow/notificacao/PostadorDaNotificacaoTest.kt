package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import com.keyflow.notificacao.app.PostadorDaNotificacao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostadorDaNotificacaoTest {
    private val escopo = TestScope()
    private val posts = mutableListOf<Pair<Long, String>>()
    private val desenhos = mutableListOf<Int>()
    private var permitida = true
    private var falharPost = false
    private var render: (Int) -> String = { "estado $it" }
    private val erros = mutableListOf<Throwable>()
    private val p = PostadorDaNotificacao(
        escopo, construir = { e: Int -> desenhos += e; render(e) }, minima = { "mínima" },
        permitida = { permitida }, publicar = { n: String ->
            if (falharPost) throw SecurityException("permissão revogada")
            posts += escopo.currentTime to n
        }, falha = { erros += it }, dispatcher = StandardTestDispatcher(escopo.testScheduler),
    )
    @After fun limpar() { p.encerrar(); escopo.cancel() }
    private fun iniciar() { p.inicial(0); p.evento(EventoPresenca.SessaoIniciou) }

    @Test fun `vinte pedidos em um segundo coalescem e o ultimo vence`() {
        iniciar()
        repeat(20) { p.solicitar(it); escopo.advanceTimeBy(50); escopo.runCurrent() }
        escopo.advanceUntilIdle()
        assertThat(posts.size).isIn(1..5)
        assertThat(posts.last().second).isEqualTo("estado 19")
        posts.zipWithNext().forEach { (a, b) -> assertThat(b.first-a.first).isAtLeast(250L) }
    }
    @Test fun `mesmo retrato visivel nao desenha nem publica de novo`() {
        iniciar(); escopo.advanceUntilIdle()
        repeat(20) { p.solicitar(0) }; escopo.advanceUntilIdle()
        assertThat(posts).hasSize(1); assertThat(desenhos).containsExactly(0)
    }
    @Test fun `dispensar impede inclusive o pedido que ja estava na fila`() {
        iniciar(); escopo.advanceUntilIdle(); posts.clear()
        p.solicitar(1); escopo.runCurrent(); p.evento(EventoPresenca.Dispensou)
        p.solicitar(2); escopo.advanceUntilIdle()
        assertThat(posts).isEmpty(); assertThat(p.podePostar).isFalse()
        p.evento(EventoPresenca.Necessaria); escopo.advanceUntilIdle()
        assertThat(posts.map { it.second }).containsExactly("estado 2")
    }
    @Test fun `dispensar durante o render nao ressuscita a notificacao`() {
        iniciar(); escopo.advanceUntilIdle(); posts.clear()
        render = { p.evento(EventoPresenca.Dispensou); "estado $it" }
        p.solicitar(1); escopo.advanceUntilIdle()
        assertThat(posts).isEmpty()
    }
    @Test fun `retrato novo durante o render descarta o antigo e entrega o ultimo`() {
        iniciar(); escopo.advanceUntilIdle(); posts.clear()
        render = { if (it == 1) p.solicitar(2); "estado $it" }
        p.solicitar(1); escopo.advanceUntilIdle()
        assertThat(posts.map { it.second }).containsExactly("estado 2")
    }
    @Test fun `aba aberta repoe somente em uma sessao viva`() {
        p.solicitar(0); p.evento(EventoPresenca.AbaAberta); escopo.advanceUntilIdle(); assertThat(posts).isEmpty()
        iniciar(); escopo.advanceUntilIdle(); posts.clear()
        p.evento(EventoPresenca.Dispensou); p.evento(EventoPresenca.AbaAberta); escopo.advanceUntilIdle()
        assertThat(posts).hasSize(1)
        p.evento(EventoPresenca.AbaAberta); escopo.advanceUntilIdle(); assertThat(posts).hasSize(1)
        p.evento(EventoPresenca.SessaoTerminou); p.solicitar(1); p.evento(EventoPresenca.AbaAberta)
        escopo.advanceUntilIdle(); assertThat(posts).hasSize(1)
    }
    @Test fun `inicial e minima imediata e depois usa o cache aquecido`() {
        assertThat(p.inicial(0)).isEqualTo("mínima"); assertThat(desenhos).isEmpty()
        escopo.advanceUntilIdle(); assertThat(posts).isEmpty()
        assertThat(p.inicial(0)).isEqualTo("estado 0")
        p.evento(EventoPresenca.SessaoIniciou); escopo.advanceUntilIdle()
        assertThat(posts).isEmpty()
    }
    @Test fun `permissao negada ou notify que falha nao derrubam o postador`() {
        permitida = false; iniciar(); escopo.advanceUntilIdle(); assertThat(posts).isEmpty()
        assertThat(p.ultimaInicial).isNotNull()
        permitida = true; falharPost = true; p.solicitar(1); escopo.advanceUntilIdle()
        assertThat(posts).isEmpty(); assertThat(erros).hasSize(1)
        falharPost = false; p.solicitar(2); escopo.advanceUntilIdle(); assertThat(posts).hasSize(1)
    }
    @Test fun `aba aberta tenta publicar apos permissao concedida mesmo sem mudar o retrato`() {
        permitida = false; iniciar(); escopo.advanceUntilIdle()
        permitida = true; p.evento(EventoPresenca.AbaAberta); escopo.advanceUntilIdle()
        assertThat(posts.map { it.second }).containsExactly("estado 0")
    }
    @Test fun `falha total de render inclusive memoria cai na minima`() {
        render = { throw OutOfMemoryError("teste") }; iniciar(); escopo.advanceUntilIdle()
        assertThat(posts.map { it.second }).containsExactly("mínima")
        assertThat(erros).hasSize(1)
    }
    @Test fun `encerrar a sessao impede notify atrasado e esquece a dispensa`() {
        iniciar(); p.evento(EventoPresenca.SessaoTerminou); escopo.advanceUntilIdle(); assertThat(posts).isEmpty()
        p.inicial(1); p.evento(EventoPresenca.SessaoIniciou); escopo.advanceUntilIdle(); assertThat(posts).hasSize(1)
    }
}
