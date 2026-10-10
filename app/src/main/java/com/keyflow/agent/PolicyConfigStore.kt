package com.keyflow.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Onde mora a configuração das ferramentas (AUI-03): a chave geral, o modo de confirmação, as ferramentas desligadas e as liberadas com
 * "permitir sempre". A [ToolPolicy] lê [config] a CADA decisão, então uma mudança vale na hora — até para uma conversa em andamento.
 *
 * Nenhum segredo mora aqui (só nomes de ferramentas e dois enums): a implementação do aparelho grava no DataStore.
 */
interface PolicyConfigStore {
    /** A configuração de agora. Síncrona: a política não pode esperar o disco no meio de uma chamada. */
    val config: StateFlow<ToolPolicyConfig>

    /** Já leu o que estava gravado? Antes disso [config] é a configuração mais RESTRITA (ferramentas desligadas): falha fechado. */
    val loaded: StateFlow<Boolean>

    /** Aplica [transform] à configuração (atômico) e persiste. O valor novo já vale na volta, antes de o disco terminar. */
    fun update(transform: (ToolPolicyConfig) -> ToolPolicyConfig)

    /** Suspende até a configuração gravada ter sido lida. */
    suspend fun awaitLoaded()
}

/** Em memória e já carregada: os testes e a galeria. */
class InMemoryPolicyConfigStore(initial: ToolPolicyConfig = ToolPolicyConfig()) : PolicyConfigStore {
    private val state = MutableStateFlow(initial)

    override val config: StateFlow<ToolPolicyConfig> = state.asStateFlow()
    override val loaded: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    override fun update(transform: (ToolPolicyConfig) -> ToolPolicyConfig) {
        state.update(transform)
    }

    override suspend fun awaitLoaded() = Unit
}
