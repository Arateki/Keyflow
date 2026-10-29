package com.keyflow.bancada.executor

import com.keyflow.bancada.resultado.Execucao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** O que a interface (e o `BancadaDeviceTest`) vê da bancada. */
sealed interface EstadoBancada {
    /** Nada rodando. */
    data object Ocioso : EstadoBancada

    /** Rodando: onde está ([progresso]) e o que já foi medido ([parcial], gravado a cada caso). */
    data class Rodando(val progresso: Progresso?, val parcial: Execucao?) : EstadoBancada

    /** Acabou — completa, ou `incompleta` com o [Execucao.motivo] (Parar, limite, térmico…). */
    data class Terminou(val execucao: Execucao) : EstadoBancada

    /** A execução não pôde nem começar ou o executor lançou: [mensagem] legível, sem exceção nem pilha. */
    data class Falhou(val mensagem: String) : EstadoBancada
}

/**
 * Quem conduz uma execução (design §0): vive no **escopo do processo**, não no de uma tela — sair da aba ou girar o aparelho não cancela uma
 * bateria de 20 minutos; só [parar] cancela (BNC-02, BEX-10). A interface só observa o [estado]. Uma execução por vez.
 *
 * @param gravar onde cada passo é guardado (o repositório de execuções, no app); chamado a cada caso, para o processo morrer sem perder o medido.
 */
class BancadaRunner(
    private val scope: CoroutineScope,
    private val gravar: suspend (Execucao) -> Unit,
) {
    private val _estado = MutableStateFlow<EstadoBancada>(EstadoBancada.Ocioso)
    val estado: StateFlow<EstadoBancada> = _estado.asStateFlow()
    private var job: Job? = null

    val rodando: Boolean get() = _estado.value is EstadoBancada.Rodando

    /** Começa [plano] com [executor]; `false` (sem fazer nada) se já há uma execução em curso. */
    @Synchronized
    fun iniciar(plano: PlanoDeExecucao, executor: ExecutorBancada): Boolean {
        if (rodando) return false
        _estado.value = EstadoBancada.Rodando(null, null)
        job = scope.launch {
            try {
                val final = executor.executar(
                    plano,
                    aoMudar = { execucao ->
                        gravar(execucao)
                        _estado.value = EstadoBancada.Rodando((_estado.value as? EstadoBancada.Rodando)?.progresso, execucao)
                    },
                    aoProgredir = { progresso ->
                        _estado.value = EstadoBancada.Rodando(progresso, (_estado.value as? EstadoBancada.Rodando)?.parcial)
                    },
                )
                _estado.value = EstadoBancada.Terminou(final)
            } catch (e: CancellationException) {
                // O executor entregou o parcial `incompleta`/CANCELADA por `aoMudar` antes de repassar o cancelamento.
                val parcial = (_estado.value as? EstadoBancada.Rodando)?.parcial
                _estado.value = if (parcial != null) EstadoBancada.Terminou(parcial) else EstadoBancada.Ocioso
                throw e
            } catch (e: Exception) {
                // Um erro do executor ou da gravação é mensagem na tela, não queda do app. Só o nome da classe: a mensagem pode ecoar um segredo.
                _estado.value = EstadoBancada.Falhou(e.javaClass.simpleName)
            }
        }
        return true
    }

    /** Cancela a execução em curso: o motor é cancelado de verdade e o medido até aqui fica `incompleta`. */
    @Synchronized
    fun parar() {
        job?.cancel()
        job = null
    }

    /** A interface já mostrou o fim: volta a [EstadoBancada.Ocioso]. */
    fun reconhecer() {
        _estado.value = _estado.value.takeIf { it is EstadoBancada.Rodando } ?: EstadoBancada.Ocioso
    }
}
