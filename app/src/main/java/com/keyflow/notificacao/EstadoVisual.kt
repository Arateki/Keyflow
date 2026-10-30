package com.keyflow.notificacao

import com.keyflow.core.ButtonState
import com.keyflow.i18n.trKey

/** A aparência da orbe (a `OrbState` da orbe flutuante, sem depender do Compose). */
enum class AparenciaDaOrbe { IDLE, LISTENING, PAUSED, TRANSCRIBING }

/** O glifo desenhado no bitmap da orbe, acima do centro. */
enum class GlifoDaOrbe { PAUSA, LIXEIRA, X, OLHO }

/** O `Chronometer` da notificação (REC-06): correndo no centro, parado e miúdo abaixo do glifo, ou oculto. */
enum class CronometroDaNotificacao { OCULTO, CORRENDO_NO_CENTRO, PARADO_MIUDO }

/** [descricao] são chaves de `tr()`: quem mostra junta com [descricaoTraduzida]. */
data class EstadoVisual(
    val aparencia: AparenciaDaOrbe,
    val glifo: GlifoDaOrbe?,
    val cronometro: CronometroDaNotificacao,
    val descricao: List<String>,
) {
    fun descricaoTraduzida(traduzir: (String) -> String): String = descricao.joinToString(". ") { traduzir(it) }
}

/**
 * `ButtonState` (+ Visão) → aparência, glifo, cronômetro e descrição (design §6, NTF-07, NTF-45). O texto entre parênteses da
 * descrição só entra quando a ação de fato está atribuída a algum toque ([ConfigEfetiva.iniciaGravacao]): a descrição nunca promete o
 * que o toque não faz. Nenhum texto ditado entra aqui (NTF-17).
 */
object EstadoVisualDaNotificacao {
    fun para(estado: ButtonState, visaoAtiva: Boolean, efetiva: ConfigEfetiva): EstadoVisual {
        val grava = efetiva.iniciaGravacao
        fun com(base: String, extra: String?) = listOfNotNull(base, extra?.takeIf { grava })
        if (visaoAtiva) {
            return EstadoVisual(
                AparenciaDaOrbe.IDLE, GlifoDaOrbe.OLHO, CronometroDaNotificacao.OCULTO,
                com(trKey("Ler a tela em andamento"), trKey("Toque para encerrar")),
            )
        }
        return when (estado) {
            ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD -> EstadoVisual(
                AparenciaDaOrbe.IDLE, null, CronometroDaNotificacao.OCULTO,
                com(trKey("KeyFlow pronto"), trKey("Toque para gravar")),
            )
            ButtonState.RECORDING -> EstadoVisual(
                AparenciaDaOrbe.LISTENING, null, CronometroDaNotificacao.CORRENDO_NO_CENTRO,
                com(trKey("KeyFlow gravando"), trKey("Toque para enviar")),
            )
            ButtonState.PAUSED -> EstadoVisual(
                AparenciaDaOrbe.PAUSED, GlifoDaOrbe.PAUSA, CronometroDaNotificacao.PARADO_MIUDO,
                com(trKey("KeyFlow pausado"), trKey("Toque para enviar")),
            )
            ButtonState.SENDING -> EstadoVisual(
                AparenciaDaOrbe.TRANSCRIBING, null, CronometroDaNotificacao.OCULTO,
                listOf(trKey("KeyFlow transcrevendo")),
            )
            ButtonState.CANCELLING -> EstadoVisual(
                AparenciaDaOrbe.PAUSED, GlifoDaOrbe.LIXEIRA, CronometroDaNotificacao.PARADO_MIUDO,
                listOf(trKey("Gravação descartada. Toque para descartar agora")),
            )
            ButtonState.ERROR -> EstadoVisual(
                AparenciaDaOrbe.IDLE, GlifoDaOrbe.X, CronometroDaNotificacao.OCULTO,
                listOf(trKey("KeyFlow com erro. Toque para dispensar")),
            )
            ButtonState.PENDING_PASTE -> EstadoVisual(
                AparenciaDaOrbe.IDLE, null, CronometroDaNotificacao.OCULTO,
                listOf(trKey("Texto pendente. Toque para colar")),
            )
        }
    }
}
