package com.keyflow.agent

import com.keyflow.reasoning.ReasoningError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import org.json.JSONObject

/** Os limites de uma execução do laço (A5, SKL-06): sem eles um modelo em círculo gastaria tokens e tempo para sempre. */
data class AgentLimits(
    /** Voltas de ferramenta por mensagem do usuário: o modelo é chamado no máximo isto de vezes, e depois da última volta o laço para. */
    val maxSteps: Int = 12,

    /** Chamadas aceitas numa MESMA volta; as que passam disto recebem um erro e não rodam (contra o despejo de chamadas). */
    val maxCallsPerStep: Int = 8,

    /** O teto de tokens de cada resposta do modelo. */
    val maxTokens: Int = AgentEngine.DEFAULT_MAX_TOKENS,

    /** O tamanho aproximado, em caracteres, a partir do qual o histórico é podado ([AgentHistory.prune]). */
    val maxHistoryChars: Int = 200_000,
)

/** Como uma execução terminou sem erro. */
enum class FinishReason {
    /** O modelo respondeu sem pedir mais ferramentas. */
    COMPLETED,

    /** Chegou no limite de voltas ([AgentLimits.maxSteps]); o usuário pode pedir para continuar. */
    STEP_LIMIT,

    /** A resposta foi cortada (limite de tokens ou de contexto); uma chamada de ferramenta que estava no meio foi descartada. */
    TRUNCATED,

    /** O provedor recusou responder. */
    REFUSED,
}

/** O que o [AgentLoop] conta à tela (e, na fase 3, ao servidor MCP). */
sealed interface AgentLoopEvent {
    /** Mais uma mensagem entrou no histórico: quem guarda a conversa a acrescenta, na ordem. É a ÚNICA fonte do histórico que o laço produz. */
    data class MessageAdded(val message: AgentMessage) : AgentLoopEvent

    data class TextDelta(val text: String) : AgentLoopEvent

    /** O modelo pediu uma ferramenta ([risk] é nulo se ela não existe). Nasce o cartão (AUI-01). */
    data class ToolCallStarted(val call: AgentToolCall, val risk: ToolRisk?) : AgentLoopEvent

    data class ToolPhaseChanged(val callId: String, val phase: ToolPhase) : AgentLoopEvent

    data class ToolCallFinished(val call: AgentToolCall, val outcome: ToolOutcome) : AgentLoopEvent

    /** Terminou. [usage] soma os tokens das voltas, quando o provedor informou. */
    data class Finished(val reason: FinishReason, val usage: TokenUsage?) : AgentLoopEvent

    /** Falhou (rede, credencial, limite de taxa…). O que já foi feito continua feito e no histórico; a volta que falhou não entra nele. */
    data class Failed(val kind: ReasoningError, val message: String?) : AgentLoopEvent
}

/**
 * O laço do agente (AGT-03): envia o histórico → lê a resposta em fluxo → executa cada chamada de ferramenta pelo [ToolExecutor] (que
 * aplica a política) → devolve os resultados → repete, até o modelo responder sem pedir ferramenta, chegar no limite de voltas ou o
 * usuário cancelar. É o que `AgentController` (fase 1B) conduz.
 *
 *  - **Só executa depois que a volta terminou**: se o fluxo cair no meio, nenhuma ferramenta dessa volta rodou e ela não entra no histórico.
 *  - **Cancelar a coleta** do fluxo interrompe a geração e a ferramenta em andamento (a coroutine é uma só). As chamadas que ficaram
 *    sem resultado ganham um resultado de erro na próxima execução ([AgentHistory.repair]).
 *  - **Nada aqui derruba o laço**: ferramenta inexistente, desligada, argumento inválido, exceção, tempo esgotado e negação do usuário
 *    viram um resultado para o modelo — e ele continua.
 *  - **Resultado de ferramenta é dado** (TLS-04): o laço não interpreta o texto dele; só a [ToolPolicy] decide o que roda.
 *  - Antes de ir ao modelo, o texto do usuário e os resultados passam pelo [SecretFilter] (uma cópia: o histórico do app não muda).
 */
class AgentLoop(
    private val engine: AgentEngine,
    private val executor: ToolExecutor,
    private val limits: AgentLimits = AgentLimits(),
) {

    /** [history] precisa terminar numa mensagem do usuário (ou num resultado de ferramenta). A nova mensagem do usuário já vai nele. */
    fun run(history: List<AgentMessage>, origin: ToolOrigin = ToolOrigin.CHAT): Flow<AgentLoopEvent> = flow {
        val repaired = AgentHistory.repair(history)
        repaired.added.forEach { emit(AgentLoopEvent.MessageAdded(it)) }
        val last = repaired.messages.lastOrNull()
        if (last !is AgentMessage.User && last !is AgentMessage.ToolResult) {
            emit(AgentLoopEvent.Failed(ReasoningError.ENGINE, "A conversa precisa terminar numa mensagem do usuário."))
            return@flow
        }

        // O que o modelo vê é fixado AGORA: ferramentas e prompt não mudam no meio da execução (o provedor liga os blocos de raciocínio
        // ao prefixo que viu), e uma ferramenta desligada depois ainda é barrada na hora de rodar, pela política.
        val tools = executor.activeTools()
        val definitions = tools.map { ToolDefinition(it.name, it.description, JSONObject(it.inputSchema.toString())) } // cópia: o motor não altera o esquema da ferramenta
        val system = AgentPrompt.system(tools, limits.maxSteps)
        val working = ArrayList(AgentHistory.prune(repaired.messages, limits.maxHistoryChars))

        var inputTokens = 0
        var outputTokens = 0
        var reported = false
        var step = 0
        while (true) {
            step++
            val turn = readTurn(working.map(::forModel), definitions, system) ?: return@flow
            turn.done.usage?.let {
                reported = true
                inputTokens += it.inputTokens
                outputTokens += it.outputTokens
            }
            val usage = if (reported) TokenUsage(inputTokens, outputTokens) else null
            val stop = turn.done.stopReason

            if (turn.calls.isEmpty() || stop == AgentStopReason.REFUSAL || stop == AgentStopReason.TRUNCATED) {
                // Sem ferramenta a rodar: ou a resposta acabou, ou foi cortada/recusada — e então as chamadas (se houve) NÃO rodam: uma
                // chamada cortada no meio não é confiável, e a de uma recusa nunca deve executar.
                if (turn.text.isNotBlank()) add(working, AgentMessage.Assistant(turn.text))
                val reason = when {
                    stop == AgentStopReason.REFUSAL -> FinishReason.REFUSED
                    stop == AgentStopReason.TRUNCATED -> FinishReason.TRUNCATED
                    else -> FinishReason.COMPLETED
                }
                emit(AgentLoopEvent.Finished(reason, usage))
                return@flow
            }

            add(working, AgentMessage.Assistant(turn.text, turn.calls, turn.done.providerState))
            for ((index, call) in turn.calls.withIndex()) {
                val risk = executor.registry[call.name]?.risk
                emit(AgentLoopEvent.ToolCallStarted(call, risk))
                val outcome = if (index >= limits.maxCallsPerStep) {
                    ToolOutcome(
                        ToolStatus.ERROR,
                        "Limite de ${limits.maxCallsPerStep} chamadas por passo: esta não foi executada. Peça menos coisas por vez.",
                    )
                } else {
                    executor.execute(call, origin) { phase -> emit(AgentLoopEvent.ToolPhaseChanged(call.id, phase)) }
                }
                // O resultado entra no histórico ANTES de o cartão ser atualizado: se o cancelamento chegar entre os dois, o que importa
                // (o modelo saber que a ferramenta rodou) já está guardado.
                add(working, AgentMessage.ToolResult(call.id, outcome.content, outcome.isError, outcome.image))
                emit(AgentLoopEvent.ToolCallFinished(call, outcome))
            }
            if (step >= limits.maxSteps) {
                emit(AgentLoopEvent.Finished(FinishReason.STEP_LIMIT, usage))
                return@flow
            }
        }
    }

    private class Turn(val text: String, val calls: List<AgentToolCall>, val done: AgentEvent.Done)

    /** Uma volta do modelo: repassa o texto à tela e junta as chamadas. `null` = falhou (o [AgentLoopEvent.Failed] já foi emitido). */
    private suspend fun FlowCollector<AgentLoopEvent>.readTurn(
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        system: String,
    ): Turn? {
        val text = StringBuilder()
        val calls = ArrayList<AgentToolCall>()
        var done: AgentEvent.Done? = null
        var error: AgentEvent.Error? = null
        var crashed: String? = null
        // `catch` só pega o que o MOTOR lançar (exceção de quem coleta, a tela, passa direto — transparência do Flow); `takeWhile` para
        // na primeira falha ou no fim, porque depois de Done/Error o contrato do motor não manda mais nada.
        flow { emitAll(engine.chat(messages, tools, system, limits.maxTokens)) }
            .catch { e -> crashed = e::class.simpleName } // um motor não deveria lançar; o texto da exceção não é repetido (pode ter dado do usuário)
            .takeWhile { event ->
                when (event) {
                    is AgentEvent.TextDelta -> {
                        text.append(event.text)
                        emit(AgentLoopEvent.TextDelta(event.text))
                    }
                    is AgentEvent.ToolCall -> calls += AgentToolCall(event.id, event.name, event.argsJson)
                    is AgentEvent.Done -> done = event
                    is AgentEvent.Error -> error = event
                }
                event !is AgentEvent.Done && event !is AgentEvent.Error
            }
            .collect()
        crashed?.let {
            emit(AgentLoopEvent.Failed(ReasoningError.ENGINE, "O motor do modelo falhou ($it)."))
            return null
        }
        error?.let {
            emit(AgentLoopEvent.Failed(it.kind, it.message))
            return null
        }
        val finished = done
        if (finished == null) {
            emit(AgentLoopEvent.Failed(ReasoningError.ENGINE, "A resposta terminou sem confirmação de fim; a volta foi descartada."))
            return null
        }
        return Turn(text.toString(), calls, finished)
    }

    private suspend fun FlowCollector<AgentLoopEvent>.add(working: MutableList<AgentMessage>, message: AgentMessage) {
        working += message
        emit(AgentLoopEvent.MessageAdded(message))
    }

    /** O que sai do app: o texto do usuário e os resultados de ferramenta sem segredo (uma cópia — o histórico do app fica como está). */
    private fun forModel(message: AgentMessage): AgentMessage = when (message) {
        is AgentMessage.User -> message.copy(text = executor.secretFilter.redact(message.text))
        is AgentMessage.ToolResult -> message.copy(content = executor.secretFilter.redact(message.content))
        is AgentMessage.Assistant -> message
    }
}
