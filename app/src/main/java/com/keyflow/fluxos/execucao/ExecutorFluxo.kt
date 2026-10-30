package com.keyflow.fluxos.execucao

import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * O comandante do fluxo (FLX-17): roda as etapas em ordem, passa a saída de cada uma como entrada da seguinte e **para** na primeira
 * que falha ou é cancelada — nada de resultado parcial (D6). Não grava, não transcreve, não raciocina, não fala: só chama as
 * [Portas]. JVM puro, então testa com portas falsas e relógio virtual.
 *
 * - **Um por vez**: [executar] com um fluxo [EstadoExecucao.Rodando] devolve [ResultadoExecucao.Recusado] de imediato e não cancela o
 *   que roda. Depois de [EstadoExecucao.Falhou] ou [EstadoExecucao.Cancelado] (que ficam visíveis até a próxima execução) pode
 *   rodar outro.
 * - **Cancelar** ([cancelar] ou cancelar a coroutine de quem executa) corta a porta em curso pela própria cooperação da coroutine; a
 *   execução termina [EstadoExecucao.Cancelado], sem aviso de falha (FLX-29).
 * - **Nada derruba o app** (FLX-28): `OutOfMemoryError` e exceção da porta viram [EstadoExecucao.Falhou] (`MEMORIA`/`MOTOR`).
 * - **Tempo** (FLX-31): [Limites] por tipo de etapa; estouro vira `TEMPO`. O relógio é o da coroutine (virtual nos testes).
 * - **Imagem** (FLX-19, FLX-28): o [Valor.Imagem] é liberado uma única vez depois que a etapa seguinte o consome — em sucesso,
 *   falha, exceção ou cancelamento — ou ao fim do fluxo, se ninguém o consumiu.
 * - O executor guarda os tipos: não entrega à porta uma entrada que não é a da etapa, nem aceita dela uma saída que não é a da
 *   etapa (um fluxo inválido que escapou da validação falha com `MOTOR` em vez de quebrar uma porta).
 */
class ExecutorFluxo(private val portas: Portas) {

    private val _estado = MutableStateFlow<EstadoExecucao>(EstadoExecucao.Ocioso)

    /** Ocioso · Rodando(etapa) · Falhou · Cancelado. `Concluido` só aparece como resultado: logo depois volta a [EstadoExecucao.Ocioso]. */
    val estado: StateFlow<EstadoExecucao> = _estado.asStateFlow()

    private val trava = Any()
    private var execucao: Job? = null // guardado por [trava]
    @Volatile private var indiceAtual = 0

    suspend fun executar(fluxo: Fluxo): ResultadoExecucao {
        val primeira = fluxo.etapas.firstOrNull()
        val job = Job(currentCoroutineContext()[Job])
        synchronized(trava) {
            if (_estado.value is EstadoExecucao.Rodando) {
                job.cancel()
                return ResultadoExecucao.Recusado
            }
            if (primeira == null) {
                job.cancel()
                return ResultadoExecucao.Terminou(EstadoExecucao.Concluido(fluxo.id)) // nada a fazer
            }
            execucao = job
            indiceAtual = 0
            _estado.value = EstadoExecucao.Rodando(fluxo.id, 0, primeira.tipo)
        }

        var final: EstadoExecucao = EstadoExecucao.Cancelado(fluxo.id, 0)
        var recomecar: CancellationException? = null
        try {
            final = withContext(job) { rodar(fluxo) }
        } catch (e: CancellationException) {
            final = EstadoExecucao.Cancelado(fluxo.id, indiceAtual)
            // `job` cancelado por [cancelar] não cancela quem executa; se quem executa foi cancelado, a cancelação segue adiante.
            if (!currentCoroutineContext().isActive) recomecar = e
        } finally {
            job.cancel()
            synchronized(trava) {
                execucao = null
                _estado.value = final
                if (final is EstadoExecucao.Concluido) _estado.value = EstadoExecucao.Ocioso
            }
        }
        recomecar?.let { throw it }
        return ResultadoExecucao.Terminou(final)
    }

    /** Cancela o fluxo em curso (nada, se não há). A porta em curso é cortada e o estado final é [EstadoExecucao.Cancelado]. */
    fun cancelar() {
        synchronized(trava) { execucao }?.cancel(CancellationException("fluxo cancelado"))
    }

    private suspend fun rodar(fluxo: Fluxo): EstadoExecucao {
        var atual: Valor = Valor.Nada // o que a etapa seguinte vai receber; o executor é o dono até a porta o consumir
        try {
            for ((i, etapa) in fluxo.etapas.withIndex()) {
                indiceAtual = i
                _estado.value = EstadoExecucao.Rodando(fluxo.id, i, etapa.tipo)
                val entrada = atual
                atual = Valor.Nada
                val resultado = try {
                    executarEtapa(etapa, entrada)
                } finally {
                    liberar(entrada) // a etapa já consumiu a imagem (ou nem começou)
                }
                when (resultado) {
                    is ResultadoEtapa.Ok -> atual = resultado.valor
                    is ResultadoEtapa.Falha -> return EstadoExecucao.Falhou(fluxo.id, i, etapa.tipo, resultado.erro)
                    ResultadoEtapa.Cancelada -> return EstadoExecucao.Cancelado(fluxo.id, i)
                }
            }
            return EstadoExecucao.Concluido(fluxo.id)
        } finally {
            liberar(atual) // fluxo que acaba numa imagem, ou parou antes de a etapa seguinte pegá-la
        }
    }

    private suspend fun executarEtapa(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> {
        if (etapa is Etapa.Desconhecida) return ResultadoEtapa.Falha(ErroEtapa(CausaErro.MOTOR, tr("Esta etapa não existe nesta versão do app.")))
        val porta = portas.para(etapa.tipo) ?: return ResultadoEtapa.Falha(erroDoMotor())
        if (entrada.tipo != etapa.entrada) return ResultadoEtapa.Falha(erroDoMotor())

        val limite = Limites.de(etapa.tipo)
        val resultado = try {
            if (limite == null) porta.executar(etapa, entrada) else withTimeout(limite) { porta.executar(etapa, entrada) }
        } catch (_: TimeoutCancellationException) {
            return ResultadoEtapa.Falha(ErroEtapa(CausaErro.TEMPO, tr("Tempo esgotado.")))
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            return ResultadoEtapa.Falha(ErroEtapa(CausaErro.MEMORIA, tr("Memória insuficiente para este modelo. Tente um menor."), etapa.tipo.aba()))
        } catch (_: Exception) {
            return ResultadoEtapa.Falha(erroDoMotor()) // sem a mensagem da exceção: pode trazer corpo cru de resposta
        }

        if (resultado is ResultadoEtapa.Ok && resultado.valor.tipo != etapa.saida) {
            liberar(resultado.valor)
            return ResultadoEtapa.Falha(erroDoMotor())
        }
        return resultado
    }

    private fun erroDoMotor() = ErroEtapa(CausaErro.MOTOR, tr("Algo deu errado nesta etapa."))

    private fun liberar(valor: Valor) {
        if (valor is Valor.Imagem) valor.handle.liberar()
    }
}

/** A aba onde se resolve um problema da etapa (memória, modelo, conexão): a da capacidade que ela usa; Capturar e Inserir não têm. */
internal fun TipoEtapa.aba(): DestinoAba? = when (this) {
    TipoEtapa.DITAR -> DestinoAba.TRANSCRICAO
    TipoEtapa.VISAO -> DestinoAba.VISAO
    TipoEtapa.PERGUNTAR, TipoEtapa.AGENTE -> DestinoAba.RACIOCINIO
    TipoEtapa.FALAR -> DestinoAba.VOZ
    TipoEtapa.CAPTURAR, TipoEtapa.INSERIR, TipoEtapa.DESCONHECIDA -> null
}
