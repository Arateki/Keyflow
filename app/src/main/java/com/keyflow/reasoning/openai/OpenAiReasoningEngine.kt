package com.keyflow.reasoning.openai

import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.HttpErrorMessage
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.providers.openai.OpenAiAuthConfig
import com.keyflow.data.models.Ipv4FirstDns
import com.keyflow.reasoning.OnlineMetrics
import com.keyflow.reasoning.GenerationOptions
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
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
import org.json.JSONException
import org.json.JSONObject

/**
 * A Responses API da OpenAI (RCL-02/03/05), para o login ChatGPT e para a chave de API na mesma base de URL.
 * Tudo abaixo vem do texto literal da documentação (`openai-siwc-notes.md`):
 *
 * - corpo **enxuto** — `model`, `input`, `store:false`, `stream:true` e, só quando há system, `instructions` (item `system` em
 *   `input` é rejeitado). No login de plano não envia `max_output_tokens`, `temperature`, `top_p`, `user`…; a chave de API pode receber opções explícitas;
 * - o fluxo SSE é lido linha a linha (sem dependência nova); **só `response.completed` é sucesso** — fim sem ele é erro, e o
 *   que já chegou continua valendo;
 * - cada erro documentado vira um [ReasoningError] preservando o motivo; **nunca** troca para outra forma de cobrança;
 * - cancelar o coletor cancela a chamada HTTP de verdade.
 */
class OpenAiReasoningEngine(
    private val authorization: AuthorizationProvider,
    http: OkHttpClient,
    private val model: String,
    private val apiBaseUrl: String = OpenAiAuthConfig.apiBaseUrl,
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
) : ReasoningEngine {

    /**
     * Resposta longa não pode ser cortada por um prazo da chamada inteira; a leitura ociosa segue limitada. IPv4 antes do IPv6:
     * a rede do Yan tem IPv6 quebrado (2026-09-27) e quem tenta o IPv6 primeiro espera minutos pela conexão.
     */
    internal val client: OkHttpClient = http.newBuilder()
        .dns(Ipv4FirstDns())
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = channelFlow {
        val authorized = try {
            authorization.authorizedHeaders(ProviderIds.OPENAI)
        } catch (e: AuthException) {
            send(ReasoningEvent.Error(authError(e.kind), "autenticação: ${e.kind}"))
            return@channelFlow
        }
        val headers = authorized.headers
        val supported = if (authorized.apiKey) supportedOptions(request.options) else emptySet()
        val metrics = OnlineMetrics(request.options, supported)
        val body = bodyOf(request, supported).toRequestBody("application/json".toMediaType())
        val transport = if (request.image == null) client else client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var attempt = 0
        while (true) {
            val call = try {
                transport.newCall(
                    Request.Builder().url("$apiBaseUrl/responses")
                        .apply { headers.forEach { (name, value) -> header(name, value) } }
                        .header("Accept", "text/event-stream")
                        .post(body)
                        .build(),
                )
            } catch (e: IllegalArgumentException) {
                // O OkHttp recusa um cabeçalho com caractere que não pode ir na rede (ex.: chave colada com espaço de largura zero).
                send(ReasoningEvent.Error(ReasoningError.UNAUTHORIZED, "a credencial tem caracteres que não podem ser enviados"))
                return@channelFlow
            }
            val outcome = attemptOnce(call, metrics)
            if (outcome !is Outcome.Retry) return@channelFlow
            attempt++
            if (attempt >= MAX_ATTEMPTS) {
                send(outcome.error)
                return@channelFlow
            }
            pauseMs(BACKOFF_MS shl (attempt - 1)) // 200, 400 ms…
        }
    }

    private sealed interface Outcome {
        data object Finished : Outcome
        class Retry(val error: ReasoningEvent.Error) : Outcome
    }

    /**
     * Uma tentativa. Um "vigia" cancela a chamada HTTP quando a corrotina é cancelada — a leitura bloqueante em `Dispatchers.IO`
     * só volta quando o socket cai. Ele só cancela por cancelamento de verdade, não quando a tentativa termina sozinha.
     */
    private suspend fun ProducerScope<ReasoningEvent>.attemptOnce(call: Call, metrics: OnlineMetrics): Outcome {
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
            return withContext(Dispatchers.IO) { execute(call, metrics) { send(it) } }
        } finally {
            finished = true
            watcher.cancel()
        }
    }

    private suspend fun execute(call: Call, metrics: OnlineMetrics, send: suspend (ReasoningEvent) -> Unit): Outcome {
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (call.isCanceled()) throw CancellationException("cancelado")
            send(ReasoningEvent.Error(ReasoningError.NETWORK, "sem conexão com a OpenAI"))
            return Outcome.Finished
        }
        response.use { r ->
            if (!r.isSuccessful) {
                val failure = httpFailure(r.code, r.body?.string().orEmpty())
                if (failure.retry) return Outcome.Retry(failure.event)
                // RCL-03: 401 = a credencial não vale mais → a conexão passa a pedir login de novo.
                if (r.code == 401) authorization.invalidate(ProviderIds.OPENAI)
                send(failure.event)
                return Outcome.Finished
            }
            try {
                readStream(r.body!!.source(), metrics, send)
            } catch (e: IOException) {
                if (call.isCanceled()) throw CancellationException("cancelado")
                send(ReasoningEvent.Error(ReasoningError.NETWORK, "o fluxo foi interrompido"))
            }
        }
        return Outcome.Finished
    }

    /** SSE: linhas `event:`/`data:` e uma linha em branco fecham cada evento. Termina no `Done`/`Error`, ou no fim do corpo. */
    private suspend fun readStream(source: BufferedSource, metrics: OnlineMetrics, send: suspend (ReasoningEvent) -> Unit) {
        val data = StringBuilder()
        var eventName: String? = null
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty() && handle(eventName, data.toString(), metrics, send)) return
                    data.clear()
                    eventName = null
                }
                line.startsWith("event:") -> eventName = line.substring(6).trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
                // `:` (comentário/keep-alive) e qualquer outra coisa: ignorado.
            }
        }
        if (data.isNotEmpty() && handle(eventName, data.toString(), metrics, send)) return
        // O corpo acabou e nunca veio `response.completed`: NÃO é sucesso (a doc), mas o texto que chegou continua valendo.
        send(ReasoningEvent.Error(ReasoningError.NETWORK, "o fluxo terminou sem response.completed"))
    }

    /** Trata um evento; devolve `true` se foi terminal (já mandou `Done` ou `Error`). */
    private suspend fun handle(eventName: String?, data: String, metrics: OnlineMetrics, send: suspend (ReasoningEvent) -> Unit): Boolean {
        val json = try {
            JSONObject(data)
        } catch (e: JSONException) {
            send(ReasoningEvent.Error(ReasoningError.ENGINE, "evento inválido no fluxo"))
            return true
        }
        when (json.optString("type").ifEmpty { eventName.orEmpty() }) {
            "response.output_text.delta" -> json.optString("delta").takeIf { it.isNotEmpty() }?.let { send(ReasoningEvent.Delta(it)) }
            "response.completed" -> {
                val usage = json.optJSONObject("response")?.optJSONObject("usage")
                if (usage != null) {
                    if (usage.has("input_tokens") && !usage.isNull("input_tokens")) metrics.inputTokens = usage.optInt("input_tokens")
                    if (usage.has("output_tokens") && !usage.isNull("output_tokens")) metrics.outputTokens = usage.optInt("output_tokens")
                }
                send(metrics.event())
                send(ReasoningEvent.Done)
                return true
            }
            "response.failed" -> {
                send(metrics.event())
                val code = json.optJSONObject("response")?.optJSONObject("error")?.optString("code").orEmpty()
                if (code == "subscription_sharing_invalid_user") authorization.invalidate(ProviderIds.OPENAI)
                send(ReasoningEvent.Error(failedError(code), "a resposta falhou ($code)"))
                return true
            }
            "response.incomplete" -> {
                json.optJSONObject("response")?.optJSONObject("usage")?.let { usage ->
                    if (usage.has("input_tokens") && !usage.isNull("input_tokens")) metrics.inputTokens = usage.optInt("input_tokens")
                    if (usage.has("output_tokens") && !usage.isNull("output_tokens")) metrics.outputTokens = usage.optInt("output_tokens")
                }
                send(metrics.event())
                val reason = json.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
                send(ReasoningEvent.Error(ReasoningError.ENGINE, "a resposta ficou incompleta ($reason)"))
                return true
            }
            // `response.created`, `output_item.added`, `content_part.*`…: nada a fazer.
        }
        return false
    }

    private class HttpFailure(val event: ReasoningEvent.Error, val retry: Boolean)

    /** O erro de uma resposta HTTP antes do fluxo. Só os CÓDIGOS documentados decidem; `detail` é texto de diagnóstico. */
    private fun httpFailure(status: Int, body: String): HttpFailure {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val code = json?.optJSONObject("error")?.optString("code").orEmpty()
        val note = HttpErrorMessage.of("OpenAI", status, body)
        val kind = when {
            status == 401 -> ReasoningError.UNAUTHORIZED
            status == 403 -> when {
                code == NOT_ELIGIBLE_CODE -> ReasoningError.NOT_ELIGIBLE // explicar; entrar de novo não resolve, e nunca se repete
                code in FORBIDDEN_ENGINE_CODES -> ReasoningError.ENGINE
                else -> ReasoningError.UNAUTHORIZED
            }
            status == 429 || code == "subscription_sharing_usage_limit_exceeded" -> ReasoningError.RATE_LIMIT
            status >= 500 -> ReasoningError.NETWORK
            else -> ReasoningError.ENGINE // 400 `unsupported_capability` e o resto: não se repete o mesmo corpo
        }
        return HttpFailure(ReasoningEvent.Error(kind, note), retry = status >= 500)
    }

    private fun failedError(code: String): ReasoningError = when (code) {
        "subscription_sharing_usage_limit_exceeded" -> ReasoningError.RATE_LIMIT
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> ReasoningError.NETWORK
        NOT_ELIGIBLE_CODE -> ReasoningError.NOT_ELIGIBLE
        "subscription_sharing_invalid_user" -> ReasoningError.UNAUTHORIZED
        else -> ReasoningError.ENGINE
    }

    private fun authError(kind: AuthErrorKind): ReasoningError = when (kind) {
        AuthErrorKind.NETWORK, AuthErrorKind.TIMEOUT -> ReasoningError.NETWORK
        else -> ReasoningError.UNAUTHORIZED
    }

    private fun supportedOptions(options: GenerationOptions): Set<String> = buildSet {
        add(GenerationOptions.TEMPERATURE)
        add(GenerationOptions.TOP_P)
        add(GenerationOptions.MAX_OUTPUT_TOKENS)
        // Effort é documentado apenas para modelos de raciocínio, com suporte a "none" variável por modelo.
        if (options.thinking == true && (model.startsWith("gpt-5") || model.startsWith("gpt-6") || model.startsWith("o"))) add(GenerationOptions.THINKING)
        if (options.thinking == false && (model.startsWith("gpt-5.1") || model.startsWith("gpt-5.2") || model.startsWith("gpt-5.4") || model.startsWith("gpt-5.5") || model.startsWith("gpt-6-sol") || model.startsWith("gpt-6-luna"))) add(GenerationOptions.THINKING)
    }

    private fun bodyOf(request: ReasoningRequest, supported: Set<String>): String = JSONObject().apply {
        put("model", model)
        val content: Any = request.image?.let { image ->
            org.json.JSONArray()
                .put(JSONObject().put("type", "input_text").put("text", request.user))
                .put(JSONObject().put("type", "input_image").put("image_url", image.dataUrl()))
        } ?: request.user
        put("input", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        put("store", false)
        put("stream", true)
        request.system?.let { put("instructions", it) }
        val o = request.options
        if (GenerationOptions.TEMPERATURE in supported) o.temperature?.let { put("temperature", it) }
        if (GenerationOptions.TOP_P in supported) o.topP?.let { put("top_p", it) }
        if (GenerationOptions.MAX_OUTPUT_TOKENS in supported) o.maxOutputTokens?.let { put("max_output_tokens", it) }
        if (GenerationOptions.THINKING in supported) o.thinking?.let { put("reasoning", JSONObject().put("effort", if (it) "medium" else "none")) }
    }.toString()

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 200L
        const val READ_TIMEOUT_S = 120L
        const val NOT_ELIGIBLE_CODE = "subscription_sharing_user_not_eligible"

        /** 403 que NÃO é "sem direito": a rota/permissão do cliente está errada — erro do motor, não do usuário. */
        val FORBIDDEN_ENGINE_CODES = setOf(
            "subscription_sharing_route_not_supported", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context",
        )
    }
}
