package com.keyflow.fluxos.execucao

import com.keyflow.data.Config

/** Como uma sessão de ditado de fluxo terminou (FLX-18). Chega ao [DestinoFluxo.aoTerminar] **exatamente uma vez** por sessão. */
sealed interface ResultadoDitado {
    /** A transcrição pronta: o texto segue para a próxima etapa em vez de ser inserido no campo. */
    data class Texto(val texto: String) : ResultadoDitado

    /** Nada captado, áudio perdido, erro da transcrição ou do gravador. O aviso é do fluxo, não do coordenador. */
    data class Falha(val erro: ErroEtapa) : ResultadoDitado

    /** A gravação foi cancelada de vez (a janela de desfazer expirou ou a pessoa concluiu o cancelamento). Não é erro. */
    data object Cancelado : ResultadoDitado
}

/**
 * Para onde vai o resultado de uma sessão de gravação iniciada por um fluxo (design §7.1). É um campo ao lado da máquina de estados do
 * `OverlayCoordinator` — a `ButtonStateMachine` não muda. [config] é o modelo da etapa (nulo = a aba, pelo `configProvider`).
 */
class DestinoFluxo(
    val config: (suspend () -> Config)?,
    val aoTerminar: (ResultadoDitado) -> Unit,
)
