package com.keyflow.reasoning.google

import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.ToolDefinition
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.HttpErrorMessage
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.providers.google.GoogleAuthConfig
import com.keyflow.data.models.Ipv4FirstDns
import com.keyflow.reasoning.ReasoningError
import java.io.IOException
import java.net.URLEncoder
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

/**
 * O agente com ferramentas para o Google Gemini: a mesma `streamGenerateContent` (SSE) do [GoogleReasoningEngine], mas com
 * `tools.functionDeclarations`, partes `functionCall`/`functionResponse` e o histórico inteiro (AGT-02). O formato está em
 * [GoogleAgentWire] (com a lista das páginas oficiais conferidas e o porquê de ser a `generateContent` e não a Interactions API).
 *
 * Reaproveita, sem alterar o motor de texto: as mesmas credenciais ([AuthorizationProvider] — a chave do AI Studio vai em
 * `x-goog-api-key`; um login (OAuth) iria em `Authorization: Bearer`), a mesma URL base, o DNS IPv4 primeiro, os mesmos tempos e a mesma
 * retentativa (HTTP 5xx, até 3 tentativas com espera crescente). Duplica, de propósito, as poucas linhas de mapeamento de erro HTTP.
 *
 * Diferenças de comportamento em relação ao motor de texto, todas por causa das ferramentas:
 *  - o único fim de sucesso é um chunk com `finishReason` (ou um prompt bloqueado): um fluxo que acaba sem ele é erro `NETWORK`;
 *  - a [AgentEvent.ToolCall] sai com os argumentos inteiros (a `functionCall` chega completa num chunk só); quem a executa é o laço;
 *  - NÃO manda `maxOutputTokens`: o limite de saída muda de modelo para modelo e pedir mais que o do modelo pode ser recusado com HTTP 400 (cautela do app; ver [GoogleAgentWire.body]);
 *    o `maxTokens` do contrato é ignorado aqui;
 *  - não loga corpo de requisição nem de erro: só contagens e o código HTTP (o corpo tem texto do usuário e resultados de ferramenta).
 */
class GoogleAgentEngine(
    private val authorization: AuthorizationProvider,
    http: OkHttpClient,
    private val model: String,
    private val apiBaseUrl: String = GoogleAuthConfig.apiBaseUrl,
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
) : AgentEngine {

    private val client: OkHttpClient = http.newBuilder()
        .dns(Ipv4FirstDns())
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> = channelFlow {
        val cleanModel = model.trim().removePrefix("models/")
        if (cleanModel.isEmpty() || !cleanModel.matches(MODEL_NAME_REGEX)) {
            send(AgentEvent.Error(ReasoningError.ENGINE, "Google Gemini: nome de modelo inválido ($model)"))
            return@channelFlow
        }
        val encodedModel = URLEncoder.encode(cleanModel, "UTF-8").replace("+", "%20")

        val headers = try {
            authorization.headers(ProviderIds.GOOGLE)
        } catch (e: AuthException) {
            send(AgentEvent.Error(authError(e.kind), "autenticação Google: ${e.kind}"))
            return@channelFlow
        }

        val jsonBody = GoogleAgentWire.body(system, messages, tools)
        // Sem o corpo: tem o texto do usuário e o resultado das ferramentas, e o Log.d sai também no release.
        android.util.Log.d("GoogleAgent", "modelo: $cleanModel, mensagens: ${messages.size}, ferramentas: ${tools.size}, corpo: ${jsonBody.length} caracteres")
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        var attempt = 0

        while (true) {
            val call = try {
                client.newCall(
                    Request.Builder()
                        .url("$apiBaseUrl/models/$encodedModel:streamGenerateContent?alt=sse")
                        .apply { headers.forEach { (name, value) -> header(name, value) } }
                        .header("Accept", "text/event-stream")
                        .post(body)
                        .build(),
                )
            } catch (e: IllegalArgumentException) {
                send(AgentEvent.Error(ReasoningError.UNAUTHORIZED, "credenciais Google inválidas"))
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
            send(AgentEvent.Error(ReasoningError.NETWORK, "sem conexão com o Google Gemini"))
            return Outcome.Finished
        }

        response.use { r ->
            if (!r.isSuccessful) {
                val errorBody = r.body?.string().orEmpty()
                HttpErrorMessage.log("GoogleAgent", r.code, errorBody, includeBody = false)
                val failure = httpFailure(r.code, errorBody)
                if (failure.retry) return Outcome.Retry(failure.event)
                if (r.code == 401) authorization.invalidate(ProviderIds.GOOGLE)
                send(failure.event)
                return Outcome.Finished
            }
            try {
                readStream(r.body!!.source(), send)
            } catch (e: IOException) {
                if (call.isCanceled()) throw CancellationException("cancelado")
                send(AgentEvent.Error(ReasoningError.NETWORK, "o fluxo SSE do Gemini foi interrompido"))
            }
        }
        return Outcome.Finished
    }

    private suspend fun readStream(source: BufferedSource, send: suspend (AgentEvent) -> Unit) {
        val reader = GoogleAgentWire.StreamReader()
        val data = StringBuilder()

        while (!reader.finished) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        reader.handle(data.toString()).forEach { send(it) }
                        data.clear()
                    }
                }
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
                // `:` (comentário/keep-alive), `event:` e qualquer outra coisa: ignorado (o Gemini só manda `data:`).
            }
        }
        // O último evento pode vir sem a linha em branco depois dele.
        if (!reader.finished && data.isNotEmpty()) reader.handle(data.toString()).forEach { send(it) }

        if (!reader.finished) {
            // Corte no meio: NÃO é o fim de uma resposta (ver o KDoc da classe).
            send(AgentEvent.Error(ReasoningError.NETWORK, "o fluxo SSE do Gemini terminou antes do fim da resposta"))
        }
    }

    private class HttpFailure(val event: AgentEvent.Error, val retry: Boolean)

    private fun httpFailure(status: Int, body: String): HttpFailure {
        val note = HttpErrorMessage.of("Google Gemini", status, body, includeDebugDetail = false)
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
        val MODEL_NAME_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 200L
        const val READ_TIMEOUT_S = 120L
    }
}
