package com.keyflow.reasoning.anthropic

import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.ToolDefinition
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.providers.anthropic.AnthropicAuthConfig
import com.keyflow.data.models.Ipv4FirstDns
import com.keyflow.reasoning.ReasoningError
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONObject

/**
 * O agente com ferramentas para a Anthropic: a mesma API `v1/messages` em streaming SSE do [AnthropicReasoningEngine], mas com `tools`,
 * blocos `tool_use`/`tool_result` e o histórico inteiro (AGT-02). O formato está em [AnthropicAgentWire] (com a lista das páginas da
 * documentação oficial conferidas).
 *
 * Reaproveita, sem alterar o motor de texto: as mesmas credenciais ([AuthorizationProvider] — a chave de API, ou o login, que também
 * leva a identidade do Claude Code no `system`), a mesma URL base, os mesmos cabeçalhos, o DNS IPv4 primeiro, os mesmos tempos e a
 * mesma retentativa (HTTP 5xx, inclusive 529 `overloaded_error`, até 3 tentativas com espera crescente). Duplica, de propósito, as
 * poucas linhas de apelido de modelo e de mapeamento de erro HTTP — mexer no motor de texto, usado por Testar/Visão/Fala, para
 * dividi-las valeria menos que o risco (um teste confere que os apelidos dos dois ficam iguais).
 *
 * Diferenças de comportamento em relação ao motor de texto, todas por causa das ferramentas:
 *  - um fluxo que acaba SEM `message_stop` é erro (`NETWORK`), não sucesso: uma chamada de ferramenta meio recebida jamais pode passar
 *    por volta terminada;
 *  - a [AgentEvent.ToolCall] sai só com o JSON dos argumentos inteiro (o do bloco fechado); quem a executa é o laço, nunca o motor;
 *  - não loga corpo de requisição nem de erro: só contagens e o código HTTP (o corpo tem texto do usuário e resultados de ferramenta).
 *
 * Não manda `thinking` nem `tool_choice` (como o motor de texto não manda): os modelos que raciocinam por padrão devolvem blocos
 * `thinking` assinados, que o [AnthropicAgentWire] guarda no [com.keyflow.agent.ProviderState] e devolve intactos na volta de ferramenta.
 *
 * @param eagerInputStreaming liga `eager_input_streaming` em todas as ferramentas (argumentos grandes, como uma skin, chegam conforme
 * são gerados em vez de depois de a API validar tudo); desligado por padrão porque então a API entrega o JSON já validado.
 */
class AnthropicAgentEngine(
    private val authorization: AuthorizationProvider,
    http: OkHttpClient,
    model: String,
    private val apiBaseUrl: String = AnthropicAuthConfig.apiBaseUrl,
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
    private val eagerInputStreaming: Boolean = false,
) : AgentEngine {

    private val client: OkHttpClient = http.newBuilder()
        .dns(Ipv4FirstDns())
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    // A mesma tabela do AnthropicReasoningEngine.
    private val resolvedModel: String = when (model) {
        "claude-3-7-sonnet" -> "claude-3-7-sonnet-20250219"
        "claude-3-5-sonnet" -> "claude-3-5-sonnet-20241022"
        "claude-3-5-haiku" -> "claude-3-5-haiku-20241022"
        "claude-3-opus" -> "claude-3-opus-20240229"
        else -> model
    }

    override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> = channelFlow {
        val headers = try {
            authorization.headers(ProviderIds.ANTHROPIC)
        } catch (e: AuthException) {
            send(AgentEvent.Error(authError(e.kind), "autenticação Anthropic: ${e.kind}"))
            return@channelFlow
        }

        val isOAuth = headers["Authorization"]?.startsWith("Bearer ") == true
        val jsonBody = AnthropicAgentWire.body(resolvedModel, system, messages, tools, maxTokens, isOAuth, eagerInputStreaming)
        // Sem o corpo: tem o texto do usuário e o resultado das ferramentas, e o Log.d sai também no release.
        android.util.Log.d("AnthropicAgent", "modelo: $resolvedModel, isOAuth: $isOAuth, mensagens: ${messages.size}, ferramentas: ${tools.size}, corpo: ${jsonBody.length} caracteres")
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        var attempt = 0

        while (true) {
            val call = try {
                client.newCall(
                    Request.Builder()
                        .url("$apiBaseUrl/messages")
                        .apply { headers.forEach { (name, value) -> header(name, value) } }
                        .header("Accept", "text/event-stream")
                        .post(body)
                        .build(),
                )
            } catch (e: IllegalArgumentException) {
                send(AgentEvent.Error(ReasoningError.UNAUTHORIZED, "credenciais Anthropic inválidas"))
                return@channelFlow
            }

            val outcome = attemptOnce(call)
            if (outcome !is Outcome.Retry) return@channelFlow
            attempt++
            if (attempt >= MAX_ATTEMPTS) {
                send(outcome.error)
                return@channelFlow
            }
            pauseMs(BACKOFF_MS shl (attempt - 1))
        }
    }

    private sealed interface Outcome {
        data object Finished : Outcome
        class Retry(val error: AgentEvent.Error) : Outcome
    }

    /** Cancelar a coleta cancela a chamada HTTP (a leitura bloqueante do corpo estoura com IOException e vira cancelamento). */
    private suspend fun ProducerScope<AgentEvent>.attemptOnce(call: Call): Outcome {
        var finished = false
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                if (!finished) call.cancel()
                throw e
            }
        }
        try {
            return withContext(Dispatchers.IO) { execute(call) { send(it) } }
        } finally {
            finished = true
            watcher.cancel()
        }
    }

    private suspend fun execute(call: Call, send: suspend (AgentEvent) -> Unit): Outcome {
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (call.isCanceled()) throw CancellationException("cancelado")
            send(AgentEvent.Error(ReasoningError.NETWORK, "sem conexão com a Anthropic"))
            return Outcome.Finished
        }

        response.use { r ->
            if (!r.isSuccessful) {
                val errorBody = r.body?.string().orEmpty()
                android.util.Log.e("AnthropicAgent", "HTTP ${r.code}")
                val failure = httpFailure(r.code, errorBody)
                if (failure.retry) return Outcome.Retry(failure.event)
                if (r.code == 401) authorization.invalidate(ProviderIds.ANTHROPIC)
                send(failure.event)
                return Outcome.Finished
            }
            try {
                readStream(r.body!!.source(), send)
            } catch (e: IOException) {
                if (call.isCanceled()) throw CancellationException("cancelado")
                send(AgentEvent.Error(ReasoningError.NETWORK, "o fluxo SSE da Anthropic foi interrompido"))
            }
        }
        return Outcome.Finished
    }

    private suspend fun readStream(source: BufferedSource, send: suspend (AgentEvent) -> Unit) {
        val reader = AnthropicAgentWire.StreamReader()
        val data = StringBuilder()
        var eventName: String? = null

        while (!reader.finished) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        reader.handle(eventName, data.toString()).forEach { send(it) }
                        data.clear()
                        eventName = null
                    }
                }
                line.startsWith("event:") -> eventName = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
        }
        // O último evento pode vir sem a linha em branco depois dele.
        if (!reader.finished && data.isNotEmpty()) reader.handle(eventName, data.toString()).forEach { send(it) }

        if (!reader.finished) {
            // Corte no meio: NÃO é o fim de uma resposta (ver o KDoc da classe).
            send(AgentEvent.Error(ReasoningError.NETWORK, "o fluxo SSE da Anthropic terminou antes do fim da resposta"))
        }
    }

    private class HttpFailure(val event: AgentEvent.Error, val retry: Boolean)

    private fun httpFailure(status: Int, body: String): HttpFailure {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val message = (json?.optJSONObject("error")?.opt("message") as? String).orEmpty()
        val note = buildString {
            append("Anthropic HTTP $status")
            if (message.isNotEmpty()) append(": $message")
        }
        val kind = when {
            status == 401 || status == 403 -> ReasoningError.UNAUTHORIZED
            status == 429 -> ReasoningError.RATE_LIMIT
            status >= 500 -> ReasoningError.NETWORK
            else -> ReasoningError.ENGINE
        }
        return HttpFailure(AgentEvent.Error(kind, note), retry = status >= 500)
    }

    private fun authError(kind: AuthErrorKind): ReasoningError = when (kind) {
        AuthErrorKind.NETWORK, AuthErrorKind.TIMEOUT -> ReasoningError.NETWORK
        else -> ReasoningError.UNAUTHORIZED
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 200L
        const val READ_TIMEOUT_S = 120L
    }
}
