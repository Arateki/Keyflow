package com.keyflow.fluxos.execucao

import com.google.common.truth.Truth.assertThat
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.execucao.EstadoExecucao.Cancelado
import com.keyflow.fluxos.execucao.EstadoExecucao.Concluido
import com.keyflow.fluxos.execucao.EstadoExecucao.Falhou
import com.keyflow.fluxos.execucao.EstadoExecucao.Ocioso
import com.keyflow.fluxos.execucao.EstadoExecucao.Rodando
import com.keyflow.fluxos.execucao.ResultadoEtapa.Cancelada
import com.keyflow.fluxos.execucao.ResultadoEtapa.Falha
import com.keyflow.fluxos.execucao.ResultadoEtapa.Ok
import com.keyflow.fluxos.execucao.ResultadoExecucao.Recusado
import com.keyflow.fluxos.execucao.ResultadoExecucao.Terminou
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** O laço do executor com portas falsas e relógio virtual (FLX-17, FLX-27..29, FLX-31). */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutorFluxoTest {

    private class ImagemFalsa : ImagemHandle {
        var liberada = 0
        override fun liberar() {
            liberada++
        }
    }

    private class PortasFalsas(private val mapa: Map<TipoEtapa, PortaEtapa>) : Portas {
        override fun para(tipo: TipoEtapa): PortaEtapa? = mapa[tipo]
    }

    private fun porta(bloco: suspend (Etapa, Valor) -> ResultadoEtapa<Valor>) = object : PortaEtapa {
        override suspend fun executar(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> = bloco(etapa, entrada)
    }

    private fun executor(vararg portas: Pair<TipoEtapa, PortaEtapa>) = ExecutorFluxo(PortasFalsas(portas.toMap()))

    private fun fluxo(vararg etapas: Etapa, id: String = "f1") = Fluxo(id, "Fluxo $id", etapas = etapas.toList())

    private val ouvirResponderFalar = fluxo(Etapa.Ditar(), Etapa.Perguntar(), Etapa.Falar())
    private val lerEColar = fluxo(Etapa.Capturar, Etapa.Visao(), Etapa.Inserir)

    private fun erro(causa: CausaErro = CausaErro.REDE, destino: DestinoAba? = null) = ErroEtapa(causa, "falhou: $causa", destino)

    // ── passagem de valor ───────────────────────────────────────────────────

    @Test
    fun `Ditar, Perguntar e Falar passam o texto de uma porta a outra`() = runTest {
        val vistos = mutableListOf<String>()
        val executor = executor(
            TipoEtapa.DITAR to porta { _, entrada ->
                vistos += "ditar<-${entrada.tipo}"
                Ok(Valor.Texto("que horas são?"))
            },
            TipoEtapa.PERGUNTAR to porta { _, entrada ->
                vistos += "perguntar<-${(entrada as Valor.Texto).texto}"
                Ok(Valor.Texto("são três horas"))
            },
            TipoEtapa.FALAR to porta { _, entrada ->
                vistos += "falar<-${(entrada as Valor.Texto).texto}"
                Ok(entrada) // devolve o mesmo texto
            },
        )

        val r = executor.executar(ouvirResponderFalar)

        assertThat(r).isEqualTo(Terminou(Concluido("f1")))
        assertThat(vistos).containsExactly("ditar<-NADA", "perguntar<-que horas são?", "falar<-são três horas").inOrder()
        assertThat(executor.estado.value).isEqualTo(Ocioso)
    }

    @Test
    fun `a porta recebe a propria etapa, com o modelo e os campos dela`() = runTest {
        var recebida: Etapa? = null
        val perguntar = Etapa.Perguntar(sistema = "seja breve")
        val executor = executor(
            TipoEtapa.DITAR to porta { _, _ -> Ok(Valor.Texto("oi")) },
            TipoEtapa.PERGUNTAR to porta { etapa, _ -> recebida = etapa; Ok(Valor.Texto("ok")) },
        )
        executor.executar(fluxo(Etapa.Ditar(), perguntar))
        assertThat(recebida).isEqualTo(perguntar)
    }

    @Test
    fun `a execucao publica Rodando em cada etapa e volta a Ocioso`() = runTest {
        val executor = executor(
            TipoEtapa.DITAR to porta { _, _ -> Ok(Valor.Texto("a")) },
            TipoEtapa.PERGUNTAR to porta { _, _ -> Ok(Valor.Texto("b")) },
            TipoEtapa.FALAR to porta { _, e -> Ok(e) },
        )
        val estados = mutableListOf<EstadoExecucao>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { executor.estado.toList(estados) }

        executor.executar(ouvirResponderFalar)

        assertThat(estados.first()).isEqualTo(Ocioso)
        assertThat(estados.filterIsInstance<Rodando>())
            .containsExactly(Rodando("f1", 0, TipoEtapa.DITAR), Rodando("f1", 1, TipoEtapa.PERGUNTAR), Rodando("f1", 2, TipoEtapa.FALAR)).inOrder()
        assertThat(estados.last()).isEqualTo(Ocioso)
    }

    @Test
    fun `fluxo sem etapas conclui sem chamar porta nenhuma`() = runTest {
        assertThat(executor().executar(fluxo())).isEqualTo(Terminou(Concluido("f1")))
    }

    // ── falha ───────────────────────────────────────────────────────────────

    @Test
    fun `falha na etapa 2 impede a 3 e deixa Falhou com o indice e a causa`() = runTest {
        var falou = false
        val e = erro(CausaErro.REDE, DestinoAba.RACIOCINIO)
        val executor = executor(
            TipoEtapa.DITAR to porta { _, _ -> Ok(Valor.Texto("oi")) },
            TipoEtapa.PERGUNTAR to porta { _, _ -> Falha(e) },
            TipoEtapa.FALAR to porta { _, entrada -> falou = true; Ok(entrada) },
        )

        val r = executor.executar(ouvirResponderFalar)

        val esperado = Falhou("f1", 1, TipoEtapa.PERGUNTAR, e)
        assertThat(r).isEqualTo(Terminou(esperado))
        assertThat(executor.estado.value).isEqualTo(esperado)
        assertThat(falou).isFalse()
    }

    @Test
    fun `depois de uma falha um novo fluxo pode rodar no mesmo executor`() = runTest {
        var tentativa = 0
        val executor = executor(TipoEtapa.DITAR to porta { _, _ -> if (tentativa++ == 0) Falha(erro()) else Ok(Valor.Texto("ok")) })
        assertThat((executor.executar(fluxo(Etapa.Ditar())) as Terminou).estado).isInstanceOf(Falhou::class.java)
        assertThat(executor.estado.value).isInstanceOf(Falhou::class.java) // a falha fica visível até o próximo fluxo
        assertThat(executor.executar(fluxo(Etapa.Ditar(), id = "f2"))).isEqualTo(Terminou(Concluido("f2")))
        assertThat(executor.estado.value).isEqualTo(Ocioso)
    }

    @Test
    fun `excecao da porta vira Falhou MOTOR sem derrubar nada e sem o texto cru`() = runTest {
        val executor = executor(TipoEtapa.DITAR to porta { _, _ -> throw IllegalStateException("corpo cru da resposta 500") })
        val r = executor.executar(fluxo(Etapa.Ditar()))
        val falhou = (r as Terminou).estado as Falhou
        assertThat(falhou.erro.causa).isEqualTo(CausaErro.MOTOR)
        assertThat(falhou.erro.mensagem).doesNotContain("corpo cru")
        assertThat(falhou.erro.mensagem).isNotEmpty()
        assertThat(executor.estado.value).isEqualTo(falhou)
    }

    @Test
    fun `OutOfMemoryError da porta vira Falhou MEMORIA`() = runTest {
        val executor = executor(TipoEtapa.DITAR to porta { _, _ -> throw OutOfMemoryError("heap") })
        val falhou = (executor.executar(fluxo(Etapa.Ditar())) as Terminou).estado as Falhou
        assertThat(falhou.erro.causa).isEqualTo(CausaErro.MEMORIA)
        assertThat(falhou.erro.mensagem).isEqualTo("Memória insuficiente para este modelo. Tente um menor.")
        assertThat(falhou.erro.destino).isEqualTo(DestinoAba.TRANSCRICAO) // a aba onde se escolhe um modelo menor
    }

    @Test
    fun `tipo de etapa sem porta ou desconhecido falha com MOTOR sem chamar nada`() = runTest {
        val semPorta = executor().executar(fluxo(Etapa.Ditar()))
        assertThat(((semPorta as Terminou).estado as Falhou).erro.causa).isEqualTo(CausaErro.MOTOR)
        var chamada = false
        val desconhecida = executor(TipoEtapa.DESCONHECIDA to porta { _, _ -> chamada = true; Ok(Valor.Nada) })
            .executar(fluxo(Etapa.Desconhecida("grafo")))
        assertThat(((desconhecida as Terminou).estado as Falhou).erro.causa).isEqualTo(CausaErro.MOTOR)
        assertThat(chamada).isFalse()
    }

    @Test
    fun `a entrada de tipo errado nao chega a porta e a saida de tipo errado para o fluxo`() = runTest {
        var chamada = false
        // Perguntar na posição 0 recebe Nada (fluxo inválido que escapou): a porta nem é chamada.
        val a = executor(TipoEtapa.PERGUNTAR to porta { _, _ -> chamada = true; Ok(Valor.Texto("x")) }).executar(fluxo(Etapa.Perguntar()))
        assertThat(((a as Terminou).estado as Falhou).erro.causa).isEqualTo(CausaErro.MOTOR)
        assertThat(chamada).isFalse()
        // Ditar devolve imagem: não é o que a etapa promete.
        val img = ImagemFalsa()
        val b = executor(TipoEtapa.DITAR to porta { _, _ -> Ok(Valor.Imagem(img)) }).executar(fluxo(Etapa.Ditar(), Etapa.Falar()))
        assertThat(((b as Terminou).estado as Falhou).etapa).isEqualTo(TipoEtapa.DITAR)
        assertThat(img.liberada).isEqualTo(1)
    }

    // ── cancelamento ────────────────────────────────────────────────────────

    @Test
    fun `Cancelada da porta termina Cancelado sem aviso de falha`() = runTest {
        var seguinte = false
        val executor = executor(
            TipoEtapa.DITAR to porta { _, _ -> Cancelada },
            TipoEtapa.FALAR to porta { _, e -> seguinte = true; Ok(e) },
        )
        val r = executor.executar(fluxo(Etapa.Ditar(), Etapa.Falar()))
        assertThat(r).isEqualTo(Terminou(Cancelado("f1", 0)))
        assertThat(executor.estado.value).isEqualTo(Cancelado("f1", 0))
        assertThat(seguinte).isFalse()
    }

    @Test
    fun `cancelar corta a porta em curso e termina Cancelado`() = runTest {
        val comecou = CompletableDeferred<Unit>()
        var cortada = false
        val executor = executor(
            TipoEtapa.DITAR to porta { _, _ -> Ok(Valor.Texto("oi")) },
            TipoEtapa.PERGUNTAR to porta { _, _ ->
                try {
                    comecou.complete(Unit)
                    awaitCancellation()
                } finally {
                    cortada = true
                }
            },
        )
        val r = async { executor.executar(fluxo(Etapa.Ditar(), Etapa.Perguntar(), Etapa.Falar())) }
        comecou.await()

        executor.cancelar()

        assertThat(r.await()).isEqualTo(Terminou(Cancelado("f1", 1)))
        assertThat(cortada).isTrue()
        assertThat(executor.estado.value).isEqualTo(Cancelado("f1", 1))
    }

    @Test
    fun `cancelar sem nada rodando nao faz nada`() = runTest {
        val executor = executor(TipoEtapa.DITAR to porta { _, _ -> Ok(Valor.Texto("a")) })
        executor.cancelar()
        assertThat(executor.estado.value).isEqualTo(Ocioso)
        assertThat(executor.executar(fluxo(Etapa.Ditar()))).isEqualTo(Terminou(Concluido("f1"))) // o cancelar solto não pega o próximo
    }

    @Test
    fun `cancelar a coroutine de quem executa corta a porta e nao deixa o estado em Rodando`() = runTest {
        val comecou = CompletableDeferred<Unit>()
        var cortada = false
        val executor = executor(
            TipoEtapa.DITAR to porta { _, _ ->
                try {
                    comecou.complete(Unit)
                    awaitCancellation()
                } finally {
                    cortada = true
                }
            },
        )
        val job = launch { executor.executar(fluxo(Etapa.Ditar())) }
        comecou.await()

        job.cancel()
        job.join()

        assertThat(cortada).isTrue()
        assertThat(executor.estado.value).isEqualTo(Cancelado("f1", 0))
    }

    // ── um por vez ──────────────────────────────────────────────────────────

    @Test
    fun `um segundo executar durante um em curso e recusado e nao cancela o primeiro`() = runTest {
        val comecou = CompletableDeferred<Unit>()
        val liberar = CompletableDeferred<Unit>()
        var cortada = false
        val executor = executor(
            TipoEtapa.DITAR to porta { _, _ ->
                try {
                    comecou.complete(Unit)
                    liberar.await()
                    Ok(Valor.Texto("a"))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cortada = true
                    throw e
                }
            },
        )
        val primeiro = async { executor.executar(fluxo(Etapa.Ditar())) }
        comecou.await()

        val segundo = executor.executar(fluxo(Etapa.Ditar(), id = "f2"))

        assertThat(segundo).isEqualTo(Recusado)
        assertThat(executor.estado.value).isEqualTo(Rodando("f1", 0, TipoEtapa.DITAR))
        liberar.complete(Unit)
        assertThat(primeiro.await()).isEqualTo(Terminou(Concluido("f1")))
        assertThat(cortada).isFalse()
    }

    // ── tempos ──────────────────────────────────────────────────────────────

    @Test
    fun `os tempos maximos sao os da spec`() {
        assertThat(Limites.PERGUNTAR).isEqualTo(180.seconds)
        assertThat(Limites.VISAO).isEqualTo(180.seconds)
        assertThat(Limites.FALAR).isEqualTo(10.minutes)
        assertThat(Limites.AGENTE).isEqualTo(10.minutes)
        assertThat(Limites.de(TipoEtapa.DITAR)).isNull()
        assertThat(Limites.de(TipoEtapa.CAPTURAR)).isNull()
        assertThat(Limites.de(TipoEtapa.INSERIR)).isNull()
        assertThat(Limites.de(TipoEtapa.PERGUNTAR)).isEqualTo(Limites.PERGUNTAR)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.estouro(tipo: TipoEtapa, etapa: Etapa, fluxoEtapas: List<Etapa>, limite: kotlin.time.Duration) {
        val executor = executor(
            TipoEtapa.DITAR to porta { _, _ -> Ok(Valor.Texto("a")) },
            tipo to porta { _, _ -> awaitCancellation() },
        )
        val r = async { executor.executar(Fluxo("f1", "F", etapas = fluxoEtapas)) }
        advanceTimeBy(limite - 1.seconds)
        runCurrent()
        assertThat(executor.estado.value).isEqualTo(Rodando("f1", 1, tipo)) // ainda dentro do prazo
        advanceTimeBy(2.seconds)
        runCurrent()
        val falhou = (r.await() as Terminou).estado as Falhou
        assertThat(falhou.erro.causa).isEqualTo(CausaErro.TEMPO)
        assertThat(falhou.erro.mensagem).isEqualTo("Tempo esgotado.")
        assertThat(falhou.indice).isEqualTo(1)
        assertThat(falhou.etapa).isEqualTo(tipo)
    }

    @Test
    fun `Perguntar estoura em 180 s`() = runTest { estouro(TipoEtapa.PERGUNTAR, Etapa.Perguntar(), listOf(Etapa.Ditar(), Etapa.Perguntar()), Limites.PERGUNTAR) }

    @Test
    fun `Falar estoura em 10 min`() = runTest { estouro(TipoEtapa.FALAR, Etapa.Falar(), listOf(Etapa.Ditar(), Etapa.Falar()), Limites.FALAR) }

    @Test
    fun `Agente estoura em 10 min`() = runTest { estouro(TipoEtapa.AGENTE, Etapa.Agente(), listOf(Etapa.Ditar(), Etapa.Agente()), Limites.AGENTE) }

    @Test
    fun `Visao estoura em 180 s`() = runTest {
        val executor = executor(
            TipoEtapa.CAPTURAR to porta { _, _ -> Ok(Valor.Imagem(ImagemFalsa())) },
            TipoEtapa.VISAO to porta { _, _ -> awaitCancellation() },
        )
        val r = async { executor.executar(fluxo(Etapa.Capturar, Etapa.Visao())) }
        advanceTimeBy(Limites.VISAO + 1.seconds)
        runCurrent()
        assertThat((((r.await() as Terminou).estado) as Falhou).erro.causa).isEqualTo(CausaErro.TEMPO)
    }

    @Test
    fun `Ditar nao tem teto proprio`() = runTest {
        val executor = executor(TipoEtapa.DITAR to porta { _, _ -> delay(60.minutes); Ok(Valor.Texto("a")) })
        assertThat(executor.executar(fluxo(Etapa.Ditar()))).isEqualTo(Terminou(Concluido("f1")))
    }

    @Test
    fun `uma etapa que responde dentro do prazo nao falha`() = runTest {
        val executor = executor(
            TipoEtapa.DITAR to porta { _, _ -> Ok(Valor.Texto("a")) },
            TipoEtapa.PERGUNTAR to porta { _, _ -> delay(179.seconds); Ok(Valor.Texto("b")) },
        )
        assertThat(executor.executar(fluxo(Etapa.Ditar(), Etapa.Perguntar()))).isEqualTo(Terminou(Concluido("f1")))
    }

    // ── imagem ──────────────────────────────────────────────────────────────

    @Test
    fun `a imagem e liberada uma vez no sucesso`() = runTest {
        val img = ImagemFalsa()
        val executor = executor(
            TipoEtapa.CAPTURAR to porta { _, _ -> Ok(Valor.Imagem(img)) },
            TipoEtapa.VISAO to porta { _, e -> assertThat(img.liberada).isEqualTo(0); Ok(Valor.Texto("texto lido")) },
            TipoEtapa.INSERIR to porta { _, e -> Ok(e) },
        )
        assertThat(executor.executar(lerEColar)).isEqualTo(Terminou(Concluido("f1")))
        assertThat(img.liberada).isEqualTo(1)
    }

    @Test
    fun `a imagem e liberada uma vez na falha`() = runTest {
        val img = ImagemFalsa()
        val executor = executor(
            TipoEtapa.CAPTURAR to porta { _, _ -> Ok(Valor.Imagem(img)) },
            TipoEtapa.VISAO to porta { _, _ -> Falha(erro(CausaErro.VAZIO)) },
        )
        executor.executar(lerEColar)
        assertThat(img.liberada).isEqualTo(1)
    }

    @Test
    fun `a imagem e liberada uma vez quando a porta lanca`() = runTest {
        val img = ImagemFalsa()
        val executor = executor(
            TipoEtapa.CAPTURAR to porta { _, _ -> Ok(Valor.Imagem(img)) },
            TipoEtapa.VISAO to porta { _, _ -> throw RuntimeException("x") },
        )
        executor.executar(lerEColar)
        assertThat(img.liberada).isEqualTo(1)
    }

    @Test
    fun `a imagem e liberada uma vez no cancelamento`() = runTest {
        val img = ImagemFalsa()
        val comecou = CompletableDeferred<Unit>()
        val executor = executor(
            TipoEtapa.CAPTURAR to porta { _, _ -> Ok(Valor.Imagem(img)) },
            TipoEtapa.VISAO to porta { _, _ -> comecou.complete(Unit); awaitCancellation() },
        )
        val r = async { executor.executar(lerEColar) }
        comecou.await()
        executor.cancelar()
        r.await()
        assertThat(img.liberada).isEqualTo(1)
    }

    @Test
    fun `a imagem e liberada se o fluxo para antes de a etapa seguinte comecar`() = runTest {
        val img = ImagemFalsa()
        // sem porta para a Visão: falha antes de consumir a imagem
        val executor = executor(TipoEtapa.CAPTURAR to porta { _, _ -> Ok(Valor.Imagem(img)) })
        executor.executar(lerEColar)
        assertThat(img.liberada).isEqualTo(1)
    }

    @Test
    fun `fluxo que termina em Capturar libera a imagem ao fim`() = runTest {
        val img = ImagemFalsa()
        val executor = executor(TipoEtapa.CAPTURAR to porta { _, _ -> Ok(Valor.Imagem(img)) })
        assertThat(executor.executar(fluxo(Etapa.Capturar))).isEqualTo(Terminou(Concluido("f1")))
        assertThat(img.liberada).isEqualTo(1)
    }
}
