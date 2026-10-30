package com.keyflow.fluxos.app

import com.keyflow.core.ButtonState
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.ValidadorFluxo
import com.keyflow.fluxos.execucao.DestinoAba
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.fluxos.execucao.ExecutorFluxo
import com.keyflow.fluxos.execucao.Portas
import com.keyflow.fluxos.execucao.ResultadoExecucao
import com.keyflow.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Dono da execução no serviço: reserva o foreground entre etapas e preserva a gravação já em curso. Chamadas na main. */
class FluxoHost(
    private val scope: CoroutineScope,
    portas: Portas,
    private val estadoDoBotao: () -> ButtonState,
    private val preparar: (Fluxo) -> Boolean,
    private val primeiroPlano: () -> Boolean,
    private val publicar: (EstadoExecucao) -> Unit,
    private val avisar: (String, DestinoAba?) -> Unit,
    private val aoTerminar: () -> Unit,
) {
    private val executor = ExecutorFluxo(portas)
    private var job: Job? = null
    var ativo: Boolean = false
        private set
    var nomeDoFluxo: String? = null
        private set
    val etapaAtual: TipoEtapa? get() = (executor.estado.value as? EstadoExecucao.Rodando)?.etapa

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { executor.estado.collect(publicar) }
    }

    fun disparar(fluxo: Fluxo?): Boolean {
        if (ativo) { avisar(tr("Já há um fluxo em andamento"), null); return false }
        if (fluxo == null || !ValidadorFluxo.validar(fluxo).valido) {
            avisar(tr("Fluxo incompleto: abra a aba Fluxos"), null)
            return false
        }
        if (estadoDoBotao() in listOf(ButtonState.RECORDING, ButtonState.PAUSED)) {
            avisar(tr("Termine a gravação atual antes de rodar o fluxo."), null)
            return false
        }
        if (!preparar(fluxo)) return false
        // A reserva precede qualquer renderização: voltar a IDLE no fim do Ditar não pode encerrar o serviço.
        ativo = true
        if (!primeiroPlano()) { ativo = false; return false }
        nomeDoFluxo = fluxo.nome
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val resultado = executor.executar(fluxo)
                val fim = (resultado as? ResultadoExecucao.Terminou)?.estado
                if (fim is EstadoExecucao.Falhou) {
                    avisar(tr("O fluxo parou em “%s”: %s", fim.etapa.nomeExibido(), fim.erro.mensagem), fim.erro.destino)
                }
            } finally {
                ativo = false
                publicar(executor.estado.value)
                aoTerminar()
            }
        }
        return true
    }

    fun cancelar() {
        executor.cancelar()
        job?.cancel()
    }
}

/** Rótulo da etapa usado pelo aviso de falha e pelo glifo acessível da orbe. */
fun TipoEtapa.nomeExibido(): String = when (this) {
    TipoEtapa.DITAR -> tr("Ditar")
    TipoEtapa.CAPTURAR -> tr("Capturar")
    TipoEtapa.VISAO -> tr("Visão")
    TipoEtapa.PERGUNTAR -> tr("Perguntar")
    TipoEtapa.FALAR -> tr("Falar")
    TipoEtapa.INSERIR -> tr("Inserir")
    TipoEtapa.AGENTE -> tr("Agente")
    TipoEtapa.DESCONHECIDA -> tr("Etapa desconhecida: remova-a")
}
