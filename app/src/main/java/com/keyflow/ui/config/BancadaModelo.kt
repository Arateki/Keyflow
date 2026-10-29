package com.keyflow.ui.config

import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.bancada.executor.ModeloEscolhido
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelKind

/**
 * O modelo que a bancada vai testar: o da origem ativa (BNC-01). Local = o modelo de texto instalado e selecionado (com o backend CPU/GPU que o card
 * mostra); nuvem = o modelo escrito nos ajustes do provedor. `null` = nenhum escolhido (as sub-abas ficam desabilitadas com o motivo de sempre).
 * [porAssinatura] diz se a origem ativa está conectada por login de plano (ChatGPT, Claude, Google) e não por chave de API (BON-02).
 */
internal fun modeloEscolhido(settings: Settings, models: LocalModelsUi, porAssinatura: Boolean): ModeloEscolhido? {
    fun nuvem(origem: String, modelo: String) = modelo.trim().takeIf { it.isNotEmpty() }?.let {
        ModeloEscolhido(origem = origem, modelo = it, nome = it, backend = ModeloEscolhido.BACKEND_NUVEM, porAssinatura = porAssinatura)
    }
    return when (settings.reasoningSource) {
        ReasoningSource.CREDITS -> null
        ReasoningSource.LOCAL -> models.items.firstOrNull { it.selected && it.spec.kind == ModelKind.TEXT }?.let { item ->
            ModeloEscolhido(
                origem = ModeloEscolhido.ORIGEM_LOCAL,
                modelo = item.spec.id,
                nome = item.spec.modelName.ifBlank { item.spec.id },
                sha256 = item.spec.file(FileRole.MODEL)?.sha256,
                backend = item.backend.name,
                contextTokens = item.spec.contextTokens,
            )
        }
        ReasoningSource.OPENAI -> nuvem("openai", settings.reasoningOpenAiModel)
        ReasoningSource.ANTHROPIC -> nuvem("anthropic", settings.reasoningAnthropicModel)
        ReasoningSource.GOOGLE -> nuvem("google", settings.reasoningGoogleModel)
        ReasoningSource.CUSTOM -> nuvem("custom", settings.reasoningCustomModel)
    }
}

/**
 * O teto de saída que a requisição REALMENTE leva por chamada, ou `null` se o motor da [origem] não manda teto (vale o limite do próprio
 * modelo). O `teto de saída` do corpus não chega à nuvem (BEX-02: opção ignorada, gravada como tal); prometê-lo no consentimento seria mentir.
 */
internal fun tetoEfetivoDeSaidaNaNuvem(origem: String): Int? = if (origem == "anthropic") com.keyflow.reasoning.anthropic.MAX_TOKENS_DE_SAIDA else null
