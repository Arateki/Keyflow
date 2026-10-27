package com.keyflow.reasoning.local

import com.keyflow.reasoning.ReasoningRequest

/**
 * A biblioteca que roda o modelo (RRUN-08): LiteRT-LM hoje, outra se precisar. Nem o AIDL nem o resto do app sabem o
 * nome dela. Todas as chamadas, exceto [cancel], vêm da mesma thread (o executor único do serviço).
 */
internal interface LlmBackend {
    /** Falso para motores sem arquivo (o eco de debug): o núcleo pula a checagem de `modelPath`. */
    val requiresModelFile: Boolean get() = true

    fun load(model: LlmModel)

    /** Inferência curta e descartável, somente na pré-carga. Sem deltas ou conversa reaproveitada. */
    fun warmUp() = Unit

    /** Bloqueia até acabar, entregando os trechos em [onDelta]. Cancelável por [cancel] de outra thread. */
    fun generate(request: ReasoningRequest, onDelta: (String) -> Unit)

    /** Pode ser chamado de qualquer thread, durante [generate]; a geração deve voltar logo. */
    fun cancel()

    fun unload()
}

/** Agenda a descarga por ociosidade (R8). Falso em teste; `ScheduledExecutorService` no serviço. */
internal interface IdleScheduler {
    fun schedule(delayMs: Long, block: () -> Unit): Cancellable
}

internal fun interface Cancellable {
    fun cancel()
}
