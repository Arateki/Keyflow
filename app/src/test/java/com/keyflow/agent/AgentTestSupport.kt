package com.keyflow.agent

import com.keyflow.reasoning.ReasoningError
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject

/** Um esquema pequeno que serve para a maioria dos testes: um texto opcional de até 100 caracteres. */
internal const val TEXT_SCHEMA = """{"type":"object","properties":{"text":{"type":"string","maxLength":100}},"additionalProperties":false}"""

/**
 * Uma ferramenta de teste: o corpo é um lambda e cada execução fica em [runs]. O `@PolicyGated` no `override` é o requisito propagado
 * (só o [ToolExecutor] chama `run`; os testes passam sempre pelo executor ou pelo laço).
 */
internal class FakeTool(
    override val name: String,
    override val risk: ToolRisk = ToolRisk.READ,
    schema: String = TEXT_SCHEMA,
    override val timeoutMs: Long = AgentTool.DEFAULT_TIMEOUT_MS,
    override val maxResultChars: Int = AgentTool.DEFAULT_MAX_RESULT_CHARS,
    var summary: ((JSONObject) -> String)? = null,
    var preview: ToolPreview? = null,
    var onDescribe: (suspend (JSONObject) -> ToolDescription)? = null,
    var onRun: suspend (JSONObject) -> ToolOutput = { ToolOutput.ok("ok") },
) : AgentTool {
    override val description: String = "Ferramenta de teste $name."
    override val inputSchema: JSONObject = JSONObject(schema)
    val runs = CopyOnWriteArrayList<JSONObject>()

    override suspend fun describe(args: JSONObject, ctx: ToolContext): ToolDescription =
        onDescribe?.invoke(args) ?: summary?.let { ToolDescription(it(args), preview) } ?: super.describe(args, ctx)

    @PolicyGated
    override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
        runs += args
        return onRun(args)
    }
}

/** Uma confirmação programável: devolve [answer] e guarda cada pedido (e se dois chegaram ao mesmo tempo). */
internal class FakeConfirmation(var answer: (ConfirmationRequest) -> ConfirmationDecision = { ConfirmationDecision.ALLOW }) : Confirmation {
    val requests = CopyOnWriteArrayList<ConfirmationRequest>()
    var maxConcurrent = 0
    private var inFlight = 0
    var onRequest: suspend (ConfirmationRequest) -> Unit = {}

    override suspend fun request(request: ConfirmationRequest): ConfirmationDecision {
        requests += request
        synchronized(this) {
            inFlight++
            if (inFlight > maxConcurrent) maxConcurrent = inFlight
        }
        try {
            onRequest(request)
            return answer(request)
        } finally {
            synchronized(this) { inFlight-- }
        }
    }
}

/** Um conjunto pronto: registro + política configurável + confirmação falsa + registro de ações em memória. */
internal class Harness(
    tools: List<AgentTool>,
    config: ToolPolicyConfig = ToolPolicyConfig(),
    val confirmation: FakeConfirmation = FakeConfirmation(),
    secrets: () -> Collection<String> = { emptyList() },
    val log: InMemoryActionLog = InMemoryActionLog(),
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
) {
    @Volatile
    var config: ToolPolicyConfig = config
    val alwaysAllowedGrants = CopyOnWriteArrayList<String>()
    val registry = ToolRegistry(tools)
    val policy = ToolPolicy({ this.config }, onAlwaysAllow = { name ->
        alwaysAllowedGrants += name
        this.config = this.config.copy(alwaysAllowed = this.config.alwaysAllowed + name)
    })
    val filter = SecretFilter(secrets)
    val executor = ToolExecutor(registry, policy, confirmation, filter, log, clock = { 1_000L }, dispatcher = dispatcher)
}

/** Um motor de mentira: cada chamada a [chat] consome o próximo script (o último se repete) e guarda o que recebeu. */
internal class ScriptedEngine(private val script: (turn: Int, request: Request) -> List<AgentEvent>) : AgentEngine {
    class Request(val messages: List<AgentMessage>, val tools: List<ToolDefinition>, val system: String, val maxTokens: Int)

    val requests = CopyOnWriteArrayList<Request>()

    constructor(vararg turns: List<AgentEvent>) : this({ turn, _ -> turns[minOf(turn, turns.lastIndex)] })

    override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> = flow {
        val request = Request(messages.toList(), tools, system, maxTokens)
        val turn = requests.size
        requests += request
        script(turn, request).forEach { emit(it) }
    }
}

internal fun text(vararg parts: String): List<AgentEvent> = parts.map { AgentEvent.TextDelta(it) }

internal fun call(id: String, name: String, args: String = "{}") = AgentEvent.ToolCall(id, name, args)

internal fun done(stop: AgentStopReason = AgentStopReason.END_TURN, usage: TokenUsage? = null, state: ProviderState? = null) = AgentEvent.Done(stop, usage, state)

internal fun errorEvent(kind: ReasoningError = ReasoningError.NETWORK, message: String? = "falhou") = AgentEvent.Error(kind, message)

internal fun user(text: String) = AgentMessage.User(text)

/** Valores-sentinela de segredo (formato de chave, conteúdo inventado), montados em pedaços para não parecerem um segredo de verdade no código. */
internal object Sentinels {
    val ANTHROPIC = "sk-ant-" + "api03-SENTINEL" + "abcdefghijklmnopqrstuvwxyz0123456789ABCD"
    val OPENAI = "sk-" + "proj-SENTINEL" + "abcdefghijklmnopqrstuvwxyz0123456789"
    val GOOGLE = "AIza" + "SySENTINEL_abcdefghijklmnopqrstuvwxyz123"
    val BEARER = "Bearer " + "SENTINELtokenabcdefghijklmnop1234567890"
    val JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" + "." + "eyJzdWIiOiJTRU5USU5FTCIsIm5hbWUiOiJ4In0" + "." + "SENTINELsignatureAbCdEf0123456789xyz"
    val PEM = "-----BEGIN OPENSSH " + "PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\nSENTINELSENTINELSENTINEL\n-----END OPENSSH " + "PRIVATE KEY-----"
    val GROQ = "gsk_" + "SENTINELabcdefghijklmnopqrstuvwxyz0123456789"
    val XAI = "xai-" + "SENTINELabcdefghijklmnopqrstuvwxyz0123456789ABCD"
    val ELEVEN = "sk_" + "0123456789abcdef0123456789abcdef0123456789abcdef"
    val GITHUB = "ghp_" + "SENTINELabcdefghijklmnopqrstuvwxyz0123456789"
    val AWS = "AKIA" + "SENTINEL01234567"

    /** Uma credencial do cofre, num formato que NENHUM padrão reconhece: só a troca por valor a protege. */
    const val VAULT = "kf-vault-secret-7f3a9c21d84b"

    val ALL = listOf(ANTHROPIC, OPENAI, GOOGLE, BEARER, JWT, PEM, GROQ, XAI, ELEVEN, GITHUB, AWS)
}
