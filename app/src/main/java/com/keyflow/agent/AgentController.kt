package com.keyflow.agent

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Em que ponto está o cartão de uma chamada de ferramenta no chat (AUI-01). */
enum class ToolCardPhase {
    /** O app pediu permissão ao usuário e espera a resposta. */
    AWAITING_CONFIRMATION,
    RUNNING,
    DONE,
    DENIED,
    ERROR,

    /** A política não deixou (ferramenta desligada nos Ajustes). */
    BLOCKED,

    /** O usuário cancelou antes de a chamada terminar. */
    CANCELLED,
}

/** O que o agente está fazendo agora — a orbe e a legenda da aba seguem isto (AUI-04). */
enum class AgentActivity { IDLE, THINKING, RUNNING_TOOL, AWAITING_CONFIRMATION }

/** Um aviso do app no chat (não é fala do modelo): a tela escolhe o texto, traduzido. */
enum class NoticeKind {
    /** Chegou no limite de passos de ferramenta; o usuário pode pedir para continuar. */
    STEP_LIMIT,

    /** A resposta foi cortada (limite de tokens ou de contexto). */
    TRUNCATED,

    /** O modelo recusou responder. */
    REFUSED,

    /** Um defeito inesperado interrompeu a execução e a exceção não trazia mensagem. */
    GENERATION_ERROR,
}

/** Uma linha do chat. */
sealed interface ChatItem {
    val id: Long

    /** A saudação do agente — o texto é da tela (traduzido na hora de desenhar). */
    data class Greeting(override val id: Long) : ChatItem

    data class UserMessage(override val id: Long, val text: String) : ChatItem

    data class AssistantMessage(override val id: Long, val text: String) : ChatItem

    /** Uma chamada de ferramenta. [summary] e [preview] são do APP (o que ele disse que ia fazer), nunca uma frase do modelo. */
    data class ToolCard(
        override val id: Long,
        val callId: String,
        val toolName: String,
        /** `null` se o modelo pediu uma ferramenta que não existe. */
        val risk: ToolRisk?,
        val summary: String?,
        val phase: ToolCardPhase,
        /** Uma linha do resultado (o que o usuário lê sob o cartão); só quando a chamada terminou. */
        val detail: String? = null,
        /** A prévia (a folha de contato de uma skin), da confirmação ou do resultado. */
        val image: ToolImage? = null,
    ) : ChatItem

    data class Notice(override val id: Long, val kind: NoticeKind) : ChatItem
}

/** O estado do chat para a tela. Imutável: cada mudança é uma cópia nova (a lista é pequena). */
data class AgentUiState(
    val items: List<ChatItem>,
    val activity: AgentActivity = AgentActivity.IDLE,
    /** Os tokens gastos na conversa, somados, quando o provedor informou (D10); `null` = nenhum informado. */
    val usage: TokenUsage? = null,
) {
    val busy: Boolean get() = activity != AgentActivity.IDLE
}

/**
 * Com quem a próxima mensagem conversa. Quem monta (o app, a partir dos Ajustes) decide pela origem de raciocínio (A1, AGT-05); o
 * controlador só obedece.
 */
sealed interface AgentBackend {
    /** O agente com ferramentas: histórico inteiro, laço de ferramentas, política e confirmação. */
    class WithTools(val engine: AgentEngine) : AgentBackend

    /**
     * O modo só texto de hoje: UMA chamada por mensagem, sem histórico e sem ferramentas — exatamente o chat de antes. [generate] recebe
     * o texto do usuário e devolve o fluxo do motor de texto traduzido para [AgentEvent] (`Delta` → `TextDelta`, `Done`, `Error`).
     */
    class TextOnly(val generate: (prompt: String) -> Flow<AgentEvent>) : AgentBackend

    /** Não há como responder (sem credencial, sem modelo): [message] aparece no chat, em vez de falhar em silêncio (AGT-05). */
    class Unavailable(val message: String) : AgentBackend
}

/**
 * O estado e a lógica do chat da aba KeyFlow, fora do Composable (D2, "o Composable não vira monolito"): as mensagens, os cartões de
 * ferramenta, o painel de confirmação, a atividade (a orbe), o cancelamento e o histórico que vai ao modelo a cada volta (AGT-01).
 * Testável na JVM, sem tela.
 *
 *  - [send] escolhe pelo [AgentBackend]: com ferramentas ([AgentLoop]), só texto (como antes) ou indisponível (uma mensagem clara).
 *  - O histórico ([history]) guarda TODA mensagem que o laço acrescenta ([AgentLoopEvent.MessageAdded]) — e, no começo de cada
 *    execução, o conserto da invariante chamada↔resultado ([AgentHistory.repair]) é aplicado AO histórico guardado, então o resultado
 *    sintético de uma ferramenta interrompida por um cancelamento fica logo depois da chamada dele, onde a API exige, e não no fim.
 *    As trocas do modo só texto também entram nele: voltar ao agente depois continua a conversa.
 *  - Cancelar ([cancel]) interrompe a geração e a ferramenta em andamento (uma coroutine só) e a espera pela confirmação; os cartões
 *    que estavam em andamento viram "cancelado". Sair da aba com um pedido de permissão aberto cancela a execução ([panelHidden]):
 *    ninguém aprova o que não vê.
 *  - Só uma execução por vez ([send] devolve `false` se há uma em andamento).
 *
 * @param executor o único caminho até as ferramentas; `null` = sem ferramentas (o chat de sempre).
 * @param gate a [Confirmation] que o [executor] usa — a MESMA instância, para o painel da tela responder as perguntas dele.
 * @param awaitReady espera o que precisa estar pronto antes de o agente usar a política (a configuração persistida carregada): enquanto não
 * está, nenhuma ferramenta roda.
 */
class AgentController(
    private val scope: CoroutineScope,
    private val executor: ToolExecutor? = null,
    private val gate: ConfirmationGate = ConfirmationGate(),
    private val actionLog: ActionLog? = null,
    private val limits: AgentLimits = AgentLimits(),
    private val awaitReady: suspend () -> Unit = {},
) {
    private val ids = AtomicLong(1)
    private val _state = MutableStateFlow(AgentUiState(listOf(ChatItem.Greeting(ids.getAndIncrement()))))

    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    /** O pedido de permissão aberto agora (o painel da tela), ou `null`. */
    val pendingConfirmation: StateFlow<ConfirmationRequest?> get() = gate.pending

    /** O histórico e o balão em escrita são mexidos pela execução (que no aparelho roda fora da thread principal) e por [clear]: tudo sob [lock]. */
    private val lock = Any()
    private val history = ArrayList<AgentMessage>()
    private var currentBubble: Long? = null

    @Volatile
    private var job: Job? = null

    /**
     * Cada execução ganha um número; o que sobra de uma execução cancelada (os últimos eventos e o `finally` dela) não mexe no estado da
     * seguinte nem no da conversa já limpa.
     */
    @Volatile
    private var runNumber = 0L

    init {
        gate.onRequested = ::onConfirmationRequested
    }

    /** A cópia do histórico neutro que vai ao modelo (para os testes e para quem quiser exibir). */
    fun historySnapshot(): List<AgentMessage> = synchronized(lock) { history.toList() }

    val isBusy: Boolean get() = job?.isActive == true

    /** Envia [text] ao [backend]. `false` se o texto é vazio ou já há uma execução em andamento (nada acontece). */
    fun send(text: String, backend: AgentBackend): Boolean {
        val prompt = text.trim()
        if (prompt.isEmpty() || isBusy) return false
        addItem(ChatItem.UserMessage(ids.getAndIncrement(), prompt))
        when (backend) {
            is AgentBackend.Unavailable -> addItem(ChatItem.AssistantMessage(ids.getAndIncrement(), backend.message))
            is AgentBackend.TextOnly -> start(AgentActivity.THINKING) { run -> runText(run, prompt, backend) }
            is AgentBackend.WithTools -> start(AgentActivity.THINKING) { run -> runAgent(run, prompt, backend) }
        }
        return true
    }

    /** O botão Cancelar: para a geração e a ferramenta em andamento, e a espera pelo usuário. */
    fun cancel() {
        job?.cancel()
    }

    /** "Limpar conversa": cancela o que estiver rodando, zera o histórico e o chat (volta só a saudação). */
    fun clear() {
        synchronized(lock) {
            runNumber++
            job?.cancel()
            job = null
            currentBubble = null
            history.clear()
            _state.value = AgentUiState(listOf(ChatItem.Greeting(ids.getAndIncrement())))
        }
        releaseTurnState(cancelled = true)
    }

    /** O usuário respondeu ao painel de confirmação do pedido [callId]. */
    fun respond(callId: String, decision: ConfirmationDecision): Boolean = gate.respond(callId, decision)

    /** A aba saiu da tela: um pedido de permissão aberto, que ninguém vê, cancela a execução. */
    fun panelHidden() {
        if (gate.pending.value != null) cancel()
    }

    /**
     * O botão "Desfazer" do registro de ações: roda a chamada de desfazer da entrada pelo MESMO executor (política, painel de
     * confirmação e registro, como qualquer chamada) e mostra o cartão no chat. Só com o chat parado; `false` se não deu para começar.
     */
    fun undo(entry: ActionLogEntry): Boolean {
        val undo = entry.undo ?: return false
        val runner = executor ?: return false
        if (isBusy) return false
        start(AgentActivity.RUNNING_TOOL) { _ ->
            val call = AgentToolCall("undo-${entry.id}", undo.toolName, undo.argsJson)
            val cardId = ids.getAndIncrement()
            addItem(ChatItem.ToolCard(cardId, call.id, call.name, runner.registry[call.name]?.risk, summary = null, phase = ToolCardPhase.RUNNING))
            val outcome = runner.execute(call, ToolOrigin.CHAT) { phase -> onPhase(call.id, phase) }
            finishCard(call.id, outcome)
            if (outcome.status == ToolStatus.OK) actionLog?.clearUndo(entry.id)
        }
        return true
    }

    // ── execuções ────────────────────────────────────────────────────────────

    private fun start(initial: AgentActivity, block: suspend (run: Long) -> Unit) {
        val mine = synchronized(lock) { ++runNumber }
        setActivity(initial)
        job = scope.launch {
            var cancelled = false
            try {
                block(mine)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                // Um defeito inesperado vira texto no chat, nunca um crash da aba. A mensagem é a da exceção (como o chat de antes).
                if (mine == runNumber) {
                    val message = e.message
                    if (message != null) appendToBubble("\n[$message]") else addItem(ChatItem.Notice(ids.getAndIncrement(), NoticeKind.GENERATION_ERROR))
                }
            } finally {
                if (mine == runNumber) finishRun(cancelled) else releaseTurnState(cancelled = true)
            }
        }
    }

    /** Fim de uma execução (qualquer que seja o motivo): solta o estado do turno, para a atividade e fecha o que ficou em andamento. */
    private fun finishRun(cancelled: Boolean) {
        synchronized(lock) { currentBubble = null }
        _state.update { s ->
            s.copy(
                activity = AgentActivity.IDLE,
                items = s.items.map { item ->
                    if (item is ChatItem.ToolCard && (item.phase == ToolCardPhase.RUNNING || item.phase == ToolCardPhase.AWAITING_CONFIRMATION)) item.copy(phase = ToolCardPhase.CANCELLED) else item
                },
            )
        }
        releaseTurnState(cancelled)
    }

    /**
     * As ferramentas que guardam estado enquanto o turno dura (os arquivos de uma skin em lotes) o soltam; a que tem um efeito só para
     * quando o turno termina bem (`open_tab`) sabe, por [cancelled], se deve aplicá-lo ou descartá-lo.
     */
    private fun releaseTurnState(cancelled: Boolean) {
        executor?.registry?.all()?.forEach { tool ->
            val scoped = tool as? TurnScoped ?: return@forEach
            if (cancelled) scoped.turnCancelled() else scoped.turnEnded()
        }
    }

    /** O modo só texto: uma chamada por mensagem, sem histórico — o chat de hoje, com o mesmo fluxo e o mesmo texto de erro. */
    private suspend fun runText(run: Long, prompt: String, backend: AgentBackend.TextOnly) {
        val bubble = ids.getAndIncrement()
        synchronized(lock) {
            if (run != runNumber) return
            history += AgentMessage.User(prompt)
            currentBubble = bubble
        }
        addItem(ChatItem.AssistantMessage(bubble, ""))
        var failed = false
        var answer = ""
        backend.generate(prompt).collect { event ->
            if (run != runNumber) return@collect
            when (event) {
                is AgentEvent.TextDelta -> {
                    answer += event.text
                    appendToBubble(event.text)
                }
                is AgentEvent.Error -> {
                    failed = true
                    appendToBubble("\n[" + (event.message ?: event.kind.name) + "]")
                }
                is AgentEvent.Done, is AgentEvent.ToolCall -> Unit // o modo só texto não tem ferramentas
            }
        }
        if (!failed && answer.isNotBlank()) synchronized(lock) { if (run == runNumber) history += AgentMessage.Assistant(answer) }
    }

    /** O agente com ferramentas: o laço inteiro, com o histórico acumulado. */
    private suspend fun runAgent(run: Long, prompt: String, backend: AgentBackend.WithTools) {
        val runner = checkNotNull(executor) { "agente sem executor de ferramentas" }
        awaitReady()
        val sent = synchronized(lock) {
            if (run != runNumber) return
            // Conserta ANTES de acrescentar a nova mensagem: o resultado sintético de uma chamada interrompida tem de ficar logo depois dela.
            val repaired = AgentHistory.repair(history).messages
            history.clear()
            history += repaired
            history += AgentMessage.User(prompt)
            currentBubble = null
            history.toList()
        }
        AgentLoop(backend.engine, runner, limits).run(sent).collect { onLoopEvent(run, it) }
    }

    private fun onLoopEvent(run: Long, event: AgentLoopEvent) {
        if (run != runNumber) return
        when (event) {
            is AgentLoopEvent.MessageAdded -> synchronized(lock) { if (run == runNumber) history += event.message }
            is AgentLoopEvent.TextDelta -> appendToBubble(event.text)
            is AgentLoopEvent.ToolCallStarted -> {
                synchronized(lock) { currentBubble = null }
                addItem(ChatItem.ToolCard(ids.getAndIncrement(), event.call.id, event.call.name, event.risk, summary = null, phase = ToolCardPhase.RUNNING))
                setActivity(AgentActivity.RUNNING_TOOL)
            }
            is AgentLoopEvent.ToolPhaseChanged -> onPhase(event.callId, event.phase)
            is AgentLoopEvent.ToolCallFinished -> {
                finishCard(event.call.id, event.outcome)
                setActivity(AgentActivity.THINKING)
            }
            is AgentLoopEvent.Finished -> {
                synchronized(lock) { currentBubble = null }
                event.usage?.let { total -> _state.update { it.copy(usage = add(it.usage, total)) } }
                val notice = when (event.reason) {
                    FinishReason.STEP_LIMIT -> NoticeKind.STEP_LIMIT
                    FinishReason.TRUNCATED -> NoticeKind.TRUNCATED
                    FinishReason.REFUSED -> NoticeKind.REFUSED
                    FinishReason.COMPLETED -> null
                }
                notice?.let { addItem(ChatItem.Notice(ids.getAndIncrement(), it)) }
            }
            // Os erros aparecem como no chat de antes (AGT-06): entre colchetes, com a mensagem do motor, ou o nome do tipo.
            is AgentLoopEvent.Failed -> appendToBubble("\n[" + (event.message ?: event.kind.name) + "]")
        }
    }

    private fun onPhase(callId: String, phase: ToolPhase) {
        when (phase) {
            ToolPhase.AWAITING_CONFIRMATION -> {
                updateCard(callId) { it.copy(phase = ToolCardPhase.AWAITING_CONFIRMATION) }
                setActivity(AgentActivity.AWAITING_CONFIRMATION)
            }
            ToolPhase.RUNNING -> {
                updateCard(callId) { it.copy(phase = ToolCardPhase.RUNNING) }
                setActivity(AgentActivity.RUNNING_TOOL)
            }
        }
    }

    /** O pedido de permissão nasceu: o cartão da chamada mostra o resumo e a prévia que o APP escreveu. */
    private fun onConfirmationRequested(request: ConfirmationRequest) {
        updateCard(request.callId) { card ->
            card.copy(summary = request.summary, image = request.preview?.toImage() ?: card.image)
        }
    }

    private fun finishCard(callId: String, outcome: ToolOutcome) {
        updateCard(callId) { card ->
            val phase = when (outcome.status) {
                ToolStatus.OK -> ToolCardPhase.DONE
                ToolStatus.ERROR -> ToolCardPhase.ERROR
                ToolStatus.DENIED -> ToolCardPhase.DENIED
                ToolStatus.BLOCKED -> ToolCardPhase.BLOCKED
            }
            card.copy(
                phase = phase,
                summary = outcome.summary ?: card.summary,
                // O texto de negação e o de "desligada" são para o MODELO; o usuário já lê o estado no cartão.
                detail = if (outcome.status == ToolStatus.OK || outcome.status == ToolStatus.ERROR) firstLine(outcome.content) else null,
                image = outcome.image ?: card.image,
            )
        }
    }

    // ── estado ───────────────────────────────────────────────────────────────

    private fun addItem(item: ChatItem) = _state.update { it.copy(items = it.items + item) }

    private fun setActivity(activity: AgentActivity) = _state.update { if (it.activity == activity) it else it.copy(activity = activity) }

    private fun updateCard(callId: String, change: (ChatItem.ToolCard) -> ChatItem.ToolCard) = _state.update { s ->
        val index = s.items.indexOfLast { it is ChatItem.ToolCard && it.callId == callId }
        if (index < 0) s else s.copy(items = s.items.toMutableList().also { it[index] = change(it[index] as ChatItem.ToolCard) })
    }

    /** Acrescenta [text] ao balão do modelo que está sendo escrito, criando-o se ainda não existe. */
    private fun appendToBubble(text: String) {
        val existing: Long? = synchronized(lock) {
            val current = currentBubble
            if (current == null) {
                val fresh = ids.getAndIncrement()
                currentBubble = fresh
                addItem(ChatItem.AssistantMessage(fresh, text.trimStart('\n')))
            }
            current
        }
        if (existing == null) return
        _state.update { s ->
            val index = s.items.indexOfLast { it.id == existing }
            if (index < 0) s else s.copy(items = s.items.toMutableList().also { list -> (list[index] as? ChatItem.AssistantMessage)?.let { list[index] = it.copy(text = it.text + text) } })
        }
    }

    private fun add(total: TokenUsage?, more: TokenUsage): TokenUsage =
        if (total == null) more else TokenUsage(total.inputTokens + more.inputTokens, total.outputTokens + more.outputTokens)

    private fun firstLine(text: String): String? = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.let { if (it.length > DETAIL_CHARS) it.take(DETAIL_CHARS) + "…" else it }

    private fun ToolPreview.toImage(): ToolImage? = runCatching { ToolImage(mediaType, bytes) }.getOrNull()

    private companion object {
        const val DETAIL_CHARS = 200
    }
}
