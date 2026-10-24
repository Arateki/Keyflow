package com.keyflow.agent

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** O que a chamada deu, para o cartão da UI e para o histórico. */
enum class ToolStatus {
    OK,

    /** A ferramenta não existe, os argumentos não valem, ou ela falhou. O modelo recebe o motivo e pode se corrigir. */
    ERROR,

    /** O USUÁRIO negou a confirmação. */
    DENIED,

    /** A política não deixou: ferramenta desligada nos Ajustes. */
    BLOCKED,
}

/** Em que ponto uma chamada está — a UI pinta o cartão (AUI-01). */
enum class ToolPhase { AWAITING_CONFIRMATION, RUNNING }

/**
 * O resultado de uma chamada, já pronto para o modelo: [content] passou pelo filtro de segredos e pelo corte de tamanho. [summary] é o
 * resumo que o app gerou (só nas ferramentas que alteram algo) e [undo] a chamada que desfaz, quando existe.
 */
class ToolOutcome(
    val status: ToolStatus,
    val content: String,
    val summary: String? = null,
    val undo: UndoCall? = null,
    /** A imagem que a ferramenta devolveu (já dentro do limite de tamanho); a tela a mostra no cartão e o adaptador do provedor a envia ao modelo, se ele aceitar. */
    val image: ToolImage? = null,
) {
    val isError: Boolean get() = status != ToolStatus.OK
}

/**
 * O ÚNICO caminho até [AgentTool.run] (D2): resolve a ferramenta, aplica a [ToolPolicy], valida os argumentos, pergunta ao usuário
 * quando a política manda, executa com tempo limite fora da thread principal, filtra e corta o resultado e registra a ação. O chat
 * ([AgentLoop]) e, na fase 3, o servidor MCP chamam [execute] — nenhum deles chama uma ferramenta direto.
 *
 * Em ordem, e parando no primeiro que falha (a ferramenta NÃO roda):
 *  1. a ferramenta existe? 2. a política deixa (ligada)? 3. os argumentos valem o esquema? 4. nenhum argumento é um segredo?
 *  5. o app descreve a ação (`WRITE`/`DESTRUCTIVE`, e a leitura quando a política manda perguntar) e, se a política manda, o usuário
 *     permite; 6. ainda está ligada? 7. roda.
 *
 * Toda falha vira um [ToolOutcome] de erro para o modelo — nada aqui lança, exceto o cancelamento, que sempre passa.
 */
class ToolExecutor(
    val registry: ToolRegistry,
    val policy: ToolPolicy,
    private val confirmation: Confirmation,
    val secretFilter: SecretFilter,
    private val actionLog: ActionLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rateClock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val maxCallsPerMinute: Int = DEFAULT_MAX_CALLS_PER_MINUTE,
    /** Onde `run` executa (TLS-06): nunca a thread principal. Os testes injetam um dispatcher de teste. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init { require(maxCallsPerMinute > 0) { "maxCallsPerMinute precisa ser positivo" } }

    /** Uma pergunta por vez: o painel de confirmação é um só, e duas chamadas (chat e MCP) não se atropelam nele. */
    private val confirmLock = Mutex()
    private val rateLock = Any()
    private val callTimes = java.util.ArrayDeque<Long>()

    /** As ferramentas LIGADAS agora. */
    fun activeTools(): List<AgentTool> = registry.active(policy::isEnabled)

    /** As mesmas, no formato que vai ao modelo. */
    fun definitions(): List<ToolDefinition> = registry.definitions(policy::isEnabled)

    suspend fun execute(
        call: AgentToolCall,
        origin: ToolOrigin = ToolOrigin.CHAT,
        onPhase: suspend (ToolPhase) -> Unit = {},
    ): ToolOutcome {
        if (!admitCall()) {
            return refuse(ToolStatus.ERROR, "Limite de $maxCallsPerMinute chamadas de ferramenta por minuto atingido. Aguarde antes de tentar novamente.")
        }
        val tool = registry[call.name]
            ?: return refuse(ToolStatus.ERROR, "Ferramenta desconhecida: «${clip(call.name, NAME_ECHO)}». ${availableTools()}")

        val decision = policy.decide(tool, origin)
        if (decision is PolicyDecision.Deny) return blocked(tool)

        val args = when (val parsed = registry.parseArgs(tool, call.argsJson)) {
            is ParsedArgs.Rejected -> return refuse(ToolStatus.ERROR, "Argumentos inválidos para ${tool.name}: ${parsed.reason}. Corrija e chame de novo.")
            is ParsedArgs.Valid -> parsed.args
        }
        // TLS-03: nenhuma ferramenta ACEITA segredo. O texto do argumento nunca é repetido na resposta.
        if (secretFilter.containsSecret(call.argsJson)) {
            return refuse(ToolStatus.ERROR, "Chamada recusada: os argumentos parecem conter um segredo (chave, token ou senha). Nenhuma ferramenta aceita segredos.")
        }

        val ctx = ToolContext(origin, call.id)
        val changes = tool.risk != ToolRisk.READ
        var summary: String? = null

        // Quem altera algo sempre ganha um resumo do app (vai ao registro); quem só lê o ganha quando a política manda perguntar (modo estrito).
        if (changes || decision is PolicyDecision.Ask) {
            // Descrever pode ser pesado (a prévia de uma skin é um desenho): também fora da thread principal e com o mesmo tempo limite.
            val description = try {
                withContext(dispatcher) { withTimeout(tool.timeoutMs) { tool.describe(args, ctx) } }
            } catch (e: TimeoutCancellationException) {
                return refuse(ToolStatus.ERROR, "A ferramenta ${tool.name} passou do tempo limite ao preparar o pedido.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ToolFailure) {
                // A própria ferramenta recusou antes de pedir (ex.: a skin é inválida): o modelo recebe o motivo e o usuário nem é incomodado.
                return refuse(ToolStatus.ERROR, e.message ?: "A ferramenta recusou o pedido.")
            } catch (e: Exception) {
                return refuse(ToolStatus.ERROR, "Falha interna ao preparar ${tool.name} (${e::class.simpleName}).")
            }
            summary = secretFilter.redact(description.summary).let { clip(it, SUMMARY_CHARS) }

            if (decision is PolicyDecision.Ask) {
                onPhase(ToolPhase.AWAITING_CONFIRMATION)
                val request = ConfirmationRequest(call.id, tool.name, tool.risk, summary, safePreview(description.preview), decision.canAlwaysAllow, origin)
                val answer = try {
                    confirmLock.withLock { confirmation.request(request) }
                } catch (e: CancellationException) {
                    if (changes) record(tool, origin, summary, ActionOutcome.CANCELLED, "Cancelada antes da resposta do usuário.", null, context = NonCancellable)
                    throw e
                } catch (e: Exception) {
                    ConfirmationDecision.DENY // falha fechado
                }
                if (answer == ConfirmationDecision.DENY) {
                    if (changes) record(tool, origin, summary, ActionOutcome.DENIED, "O usuário negou.", null)
                    return ToolOutcome(ToolStatus.DENIED, DENIED_TEXT, summary)
                }
                // "Permitir sempre" só vale se o painel o OFERECEU (um painel com defeito não amplia a permissão).
                if (answer == ConfirmationDecision.ALLOW_ALWAYS && decision.canAlwaysAllow) policy.grantAlways(tool, origin)
                // Segunda checagem da política depois da espera: o usuário pode ter desligado a ferramenta nos Ajustes com o painel aberto.
                if (policy.decide(tool, origin) is PolicyDecision.Deny) return blocked(tool, summary)
            }
        }

        onPhase(ToolPhase.RUNNING)
        val output = try {
            withContext(dispatcher) {
                withTimeout(tool.timeoutMs) {
                    @OptIn(PolicyGated::class)
                    tool.run(args, ctx)
                }
            }
        } catch (e: TimeoutCancellationException) {
            ToolOutput.error("A ferramenta ${tool.name} passou do tempo limite (${tool.timeoutMs / 1000} s) e foi interrompida.")
        } catch (e: CancellationException) {
            if (changes) record(tool, origin, summary ?: tool.name, ActionOutcome.CANCELLED, "Cancelada durante a execução.", null, context = NonCancellable)
            throw e
        } catch (e: ToolFailure) {
            ToolOutput.error(e.message ?: "A ferramenta falhou.")
        } catch (e: OutOfMemoryError) {
            ToolOutput.error("A ferramenta ${tool.name} ficou sem memória.")
        } catch (e: StackOverflowError) {
            ToolOutput.error("Falha interna da ferramenta ${tool.name} (recursão demais).")
        } catch (e: Exception) {
            // Só o NOME da classe: a mensagem de uma exceção de verdade pode carregar caminho, texto do usuário ou pior.
            ToolOutput.error("Falha interna da ferramenta ${tool.name} (${e::class.simpleName}).")
        }

        val content = shape(output.content, tool.maxResultChars)
        val image = output.image?.takeIf { it.bytes.size <= ToolImage.MAX_BYTES }
        val undo = output.undo?.let { UndoCall(it.toolName, secretFilter.redact(it.argsJson)) }
        val status = if (output.isError) ToolStatus.ERROR else ToolStatus.OK
        if (changes) {
            val detail = clip(content.lineSequence().firstOrNull().orEmpty(), DETAIL_CHARS)
            record(tool, origin, summary ?: tool.name, if (output.isError) ActionOutcome.ERROR else ActionOutcome.OK, detail, undo)
        }
        return ToolOutcome(status, content, summary, undo, image)
    }

    /** A legenda da prévia também é texto do app que o usuário lê; passa pelo mesmo filtro que o resumo (a imagem em si é desenhada pelo app, não por texto do modelo). */
    private fun safePreview(preview: ToolPreview?): ToolPreview? {
        val caption = preview?.caption ?: return preview
        val safe = clip(secretFilter.redact(caption), CAPTION_CHARS)
        return if (safe == caption) preview else ToolPreview(preview.mediaType, preview.bytes, safe)
    }

    private fun blocked(tool: AgentTool, summary: String? = null) = ToolOutcome(
        ToolStatus.BLOCKED,
        "A ferramenta ${tool.name} está desligada nos Ajustes do app. Não tente de novo; diga ao usuário que ela precisa ser ligada lá se ele quiser isto.",
        summary,
    )

    /** Uma falha ANTES de rodar: o texto é do app, mas passa pelo filtro como todo o resto que vai ao modelo. */
    private fun refuse(status: ToolStatus, message: String) = ToolOutcome(status, secretFilter.redact(message))

    private fun availableTools(): String {
        val names = definitions().map { it.name }
        return if (names.isEmpty()) "Nenhuma ferramenta está ativa." else "Ferramentas disponíveis: ${names.joinToString(", ")}."
    }

    /** Filtra o texto INTEIRO e só depois corta: cortar antes poderia deixar a ponta de um segredo sem o padrão que o reconheceria. */
    private fun shape(raw: String, max: Int): String {
        val filtered = secretFilter.redact(raw)
        if (filtered.length <= max) return filtered
        var cut = max
        if (cut > 0 && Character.isHighSurrogate(filtered[cut - 1])) cut--
        return filtered.substring(0, cut) + "\n[… resultado cortado: ${cut} de ${filtered.length} caracteres]"
    }

    private suspend fun record(
        tool: AgentTool,
        origin: ToolOrigin,
        summary: String,
        outcome: ActionOutcome,
        detail: String,
        undo: UndoCall?,
        context: CoroutineContext = EmptyCoroutineContext,
    ) {
        val entry = ActionLogEntry(
            atMs = clock(),
            toolName = tool.name,
            risk = tool.risk,
            summary = summary,
            outcome = outcome,
            detail = secretFilter.redact(detail),
            origin = origin,
            undo = undo,
        )
        try {
            withContext(context) { actionLog.append(entry) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Falhar em registrar não desfaz o que já aconteceu nem esconde o resultado do modelo.
        }
    }

    private fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max) + "…"

    private fun admitCall(): Boolean = synchronized(rateLock) {
        val now = rateClock()
        val cutoff = now - RATE_WINDOW_MS
        while (callTimes.peekFirst()?.let { it <= cutoff } == true) callTimes.removeFirst()
        if (callTimes.size >= maxCallsPerMinute) return@synchronized false
        callTimes.addLast(now)
        true
    }

    private companion object {
        const val NAME_ECHO = 64
        const val SUMMARY_CHARS = 600
        const val CAPTION_CHARS = 1_500
        const val DETAIL_CHARS = 200
        const val DEFAULT_MAX_CALLS_PER_MINUTE = 24
        private const val RATE_WINDOW_MS = 60_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** O que o modelo lê quando o usuário diz não (TLS-02, AUI-02). */
        const val DENIED_TEXT = "O usuário negou esta ação. Não insista nem tente de outro jeito; diga o que você ia fazer e pergunte o que ele prefere."
    }
}
