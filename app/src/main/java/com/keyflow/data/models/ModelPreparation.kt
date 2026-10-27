package com.keyflow.data.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Trabalho real em curso, sem porcentagem estimada. Uma falha termina a espera e permanece no card. */
data class ModelPreparation(val modelId: String, val startedAtMs: Long, val failure: String? = null, val requestId: Long = 0)

/** Uma resposta antiga nunca apaga o indicador da seleção mais recente, nem da mesma seleção refeita. */
class ModelPreparationTracker(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var nextRequestId = 0L
    private val mutable = MutableStateFlow<ModelPreparation?>(null)
    val state: StateFlow<ModelPreparation?> = mutable.asStateFlow()

    @Synchronized fun begin(modelId: String): ModelPreparation =
        ModelPreparation(modelId, nowMs(), requestId = ++nextRequestId).also { mutable.value = it }

    @Synchronized fun finish(request: ModelPreparation, failure: String? = null) {
        if (mutable.value === request) mutable.value = failure?.let { request.copy(failure = it) }
    }

    @Synchronized fun clear() { mutable.value = null }
}
