package com.keyflow.fluxos.execucao

import com.keyflow.fluxos.TipoEtapa

/** O estado da execução, publicado em `KeyFlowController.fluxo` para a orbe, o teclado e o card (FLX-17). */
sealed interface EstadoExecucao {
    data object Ocioso : EstadoExecucao

    /** [indice] é a posição da etapa em curso (0-based); [etapa] é o tipo, para o glifo da orbe. */
    data class Rodando(val fluxoId: String, val indice: Int, val etapa: TipoEtapa) : EstadoExecucao

    data class Falhou(val fluxoId: String, val indice: Int, val etapa: TipoEtapa, val erro: ErroEtapa) : EstadoExecucao

    data class Cancelado(val fluxoId: String, val indice: Int) : EstadoExecucao

    /** Só aparece como resultado: logo em seguida o estado volta a [Ocioso]. */
    data class Concluido(val fluxoId: String) : EstadoExecucao
}

/** O que [ExecutorFluxo.executar] devolve. */
sealed interface ResultadoExecucao {
    /** Já havia um fluxo rodando: nada foi cancelado nem iniciado (FLX-17). O host mostra "Já há um fluxo em andamento". */
    data object Recusado : ResultadoExecucao

    /** A execução acabou em [estado]: [EstadoExecucao.Concluido], [EstadoExecucao.Falhou] ou [EstadoExecucao.Cancelado]. */
    data class Terminou(val estado: EstadoExecucao) : ResultadoExecucao
}
