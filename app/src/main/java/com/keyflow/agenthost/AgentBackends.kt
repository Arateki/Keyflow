package com.keyflow.agenthost

import com.keyflow.agent.AgentBackend
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/** Como o chat conversa na origem de raciocínio de agora. */
enum class AgentMode {
    /** Não há ferramenta nenhuma no app: o chat de sempre, sem nota (nada a explicar). */
    PLAIN,

    /** O agente com ferramentas (a origem é a Anthropic, a OpenAI ou o Google, por chave de API). */
    TOOLS,

    /** Há ferramentas, mas esta origem não as suporta: só texto, com a nota que explica onde elas existem (AGT-05). */
    TEXT_ONLY,
}

/**
 * Escolhe com quem a próxima mensagem do chat conversa (D1, A1, AGT-05). **Ferramentas só quando a origem de raciocínio é a Anthropic, a
 * OpenAI ou o Google E a credencial dela é uma CHAVE de API** (A1: a chave tem formato e cobrança documentados; o login por assinatura
 * não foi validado com ferramentas — ver `STATE.md`, AGT-T7); em qualquer outra — Local, Créditos, Custom, login, sem credencial — o chat
 * cai no modo só texto de hoje ([ReasoningEngine]), e a tela diz por quê. Nunca falha em silêncio: sem motor pronto, o
 * [AgentBackend.Unavailable] leva o motivo, o mesmo que o chat sempre mostrou.
 *
 * Tudo que é do ambiente entra por lambda — o resolvedor não conhece cofre, rede nem Activity, e se testa na JVM.
 */
class AgentBackends(
    /** O app tem alguma ferramenta registrada? Sem nenhuma, o chat é o de hoje, sem nota nem agente. */
    private val hasTools: () -> Boolean,
    /** A credencial da Anthropic é uma CHAVE de API (e não um login, nem nada)? */
    private val anthropicApiKeyConnected: () -> Boolean,
    /** Avisa quando a credencial da Anthropic muda (entrar, sair, trocar a chave), para a nota da tela acompanhar. */
    private val anthropicCredentialChanges: Flow<Unit> = emptyFlow(),
    private val agentEngine: (model: String) -> AgentEngine,
    /** O mesmo resolvedor do chat de antes e da área Testar. */
    private val resolveText: (Settings) -> ReasoningResolution,
    /** A credencial da OpenAI é uma CHAVE de API? Sem [openAiAgentEngine] a origem fica no texto. */
    private val openAiApiKeyConnected: () -> Boolean = { false },
    private val openAiCredentialChanges: Flow<Unit> = emptyFlow(),
    private val openAiAgentEngine: ((model: String) -> AgentEngine)? = null,
    /** A credencial do Google é uma CHAVE de API (a do AI Studio)? Sem [googleAgentEngine] a origem fica no texto. */
    private val googleApiKeyConnected: () -> Boolean = { false },
    private val googleCredentialChanges: Flow<Unit> = emptyFlow(),
    private val googleAgentEngine: ((model: String) -> AgentEngine)? = null,
) {

    /** O modo de agora para [settings]. Barato: não cria motor nenhum. */
    fun mode(settings: Settings): AgentMode = when {
        !hasTools() -> AgentMode.PLAIN
        supportsTools(settings) -> AgentMode.TOOLS
        else -> AgentMode.TEXT_ONLY
    }

    /** O modo, mais cada vez que uma credencial muda — é o que a tela observa para mostrar (ou tirar) a nota. */
    fun modeFlow(settings: Settings): Flow<AgentMode> =
        merge(anthropicCredentialChanges, openAiCredentialChanges, googleCredentialChanges).map { mode(settings) }.onStart { emit(mode(settings)) }

    /** O que conversa com o usuário agora. Chamado na hora de ENVIAR (cria o motor). */
    fun resolve(settings: Settings): AgentBackend {
        if (hasTools()) toolEngine(settings)?.let { return AgentBackend.WithTools(it) }
        return when (val resolution = resolveText(settings)) {
            is ReasoningResolution.Ready -> textOnly(resolution.engine)
            is ReasoningResolution.Unavailable -> AgentBackend.Unavailable(unavailableMessage(resolution.reason))
        }
    }

    private fun supportsTools(settings: Settings): Boolean = when (settings.reasoningSource) {
        ReasoningSource.ANTHROPIC -> settings.reasoningAnthropicModel.isNotBlank() && anthropicApiKeyConnected()
        ReasoningSource.OPENAI -> openAiAgentEngine != null && settings.reasoningOpenAiModel.isNotBlank() && openAiApiKeyConnected()
        ReasoningSource.GOOGLE -> googleAgentEngine != null && settings.reasoningGoogleModel.isNotBlank() && googleApiKeyConnected()
        else -> false
    }

    /** O motor com ferramentas da origem de [settings], ou `null` se ela não as tem (o chat cai no texto). */
    private fun toolEngine(settings: Settings): AgentEngine? {
        if (!supportsTools(settings)) return null
        return when (settings.reasoningSource) {
            ReasoningSource.ANTHROPIC -> agentEngine(settings.reasoningAnthropicModel)
            ReasoningSource.OPENAI -> openAiAgentEngine?.invoke(settings.reasoningOpenAiModel)
            ReasoningSource.GOOGLE -> googleAgentEngine?.invoke(settings.reasoningGoogleModel)
            else -> null
        }
    }

    companion object {
        /** O prompt de sistema do chat de sempre — o modo só texto o mantém igual. */
        const val TEXT_ONLY_SYSTEM = "Você é o KeyFlow, um assistente de IA integrado ao sistema operacional Android."

        /** O chat de antes: uma chamada por mensagem ao motor de texto, sem histórico, traduzida para os eventos do agente. */
        fun textOnly(engine: ReasoningEngine): AgentBackend.TextOnly = AgentBackend.TextOnly { prompt ->
            engine.generate(ReasoningRequest(system = TEXT_ONLY_SYSTEM, user = prompt)).map { event ->
                when (event) {
                    is ReasoningEvent.Delta -> AgentEvent.TextDelta(event.text)
                    ReasoningEvent.Done -> AgentEvent.Done()
                    is ReasoningEvent.Error -> AgentEvent.Error(event.kind, event.message)
                }
            }
        }

        /** O resolvedor do chat SEM ferramentas: o de hoje (o `ConfigScreen` sem agente, as telas de teste). */
        fun plain(resolveText: (Settings) -> ReasoningResolution) = AgentBackends(
            hasTools = { false },
            anthropicApiKeyConnected = { false },
            agentEngine = { error("sem ferramentas, sem agente") },
            resolveText = resolveText,
        )

        /** Por que não há motor pronto — a mesma frase que o chat sempre mostrou. */
        fun unavailableMessage(reason: UnavailableReason): String = when (reason) {
            UnavailableReason.NO_LOCAL_MODEL -> tr("Nenhum modelo local selecionado. Acesse a aba Raciocínio para selecionar.")
            UnavailableReason.MODEL_NOT_INSTALLED -> tr("O modelo local selecionado ainda não foi baixado. Baixe-o na aba Raciocínio.")
            UnavailableReason.NOT_CONNECTED -> tr("Credenciais do modelo não configuradas. Configure a chave na aba Provedores ou Raciocínio.")
            UnavailableReason.NO_MODEL_CHOSEN -> tr("Nenhum modelo selecionado na origem ativa.")
        }
    }
}
