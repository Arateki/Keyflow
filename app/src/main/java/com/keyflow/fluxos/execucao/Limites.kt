package com.keyflow.fluxos.execucao

import com.keyflow.fluxos.TipoEtapa
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Os tempos máximos por etapa (FLX-31): passou do limite, a etapa falha com [CausaErro.TEMPO] em vez de travar a fila "um fluxo
 * por vez". Ditar vale o limite de gravação e o tempo de transcrição que já existem; Capturar espera a pessoa (cancelar encerra);
 * Inserir é instantâneo — nenhum dos três tem teto aqui.
 */
object Limites {
    val PERGUNTAR: Duration = 180.seconds
    val VISAO: Duration = 180.seconds
    val FALAR: Duration = 10.minutes

    /** Inclui esperar a confirmação de uma ferramenta. */
    val AGENTE: Duration = 10.minutes

    fun de(tipo: TipoEtapa): Duration? = when (tipo) {
        TipoEtapa.PERGUNTAR -> PERGUNTAR
        TipoEtapa.VISAO -> VISAO
        TipoEtapa.FALAR -> FALAR
        TipoEtapa.AGENTE -> AGENTE
        TipoEtapa.DITAR, TipoEtapa.CAPTURAR, TipoEtapa.INSERIR, TipoEtapa.DESCONHECIDA -> null
    }
}
