package com.keyflow.reasoning.custom

import com.keyflow.data.models.Ipv4FirstDns
import com.keyflow.reasoning.OnlineMetrics
import com.keyflow.reasoning.GenerationOptions
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Endpoint fornecido pelo usuário, no formato OpenAI Chat Completions. */
class CustomReasoningEngine(
    private val endpoint: String,
    private val model: String,
    http: OkHttpClient,
    private val headers: suspend () -> Map<String, String> = { emptyMap() },
) : ReasoningEngine {
    private val client = http.newBuilder().dns(Ipv4FirstDns()).followRedirects(false).followSslRedirects(false)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(120, TimeUnit.SECONDS).build()

    override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = channelFlow {
        val supported = setOf(GenerationOptions.TEMPERATURE, GenerationOptions.TOP_P, GenerationOptions.SEED, GenerationOptions.MAX_OUTPUT_TOKENS)
        val metrics = OnlineMetrics(request.options, supported)
        val body = bodyOf(request, supported).toRequestBody("application/json".toMediaType())
        val firstCall = try {
            val customHeaders = headers()
            client.newCall(Request.Builder().url(endpoint)
                .apply { customHeaders.forEach { (name, value) -> header(name, value) } }
                .header("Accept", "text/event-stream").post(body).build())
        } catch (_: IllegalArgumentException) {
            send(ReasoningEvent.Error(ReasoningError.ENGINE, "endpoint ou cabeçalho Custom inválido"))
            return@channelFlow
        }
        val activeCall = AtomicReference(firstCall)
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } catch (e: CancellationException) {
                if (!finished.get()) activeCall.get().cancel()
                throw e
            }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    var call = firstCall
                    var legacyLimit = false
                    while (true) {
                        activeCall.set(call)
                        currentCoroutineContext().ensureActive()
                        val retryLegacy = call.execute().use { response ->
                            if (!response.isSuccessful) {
                                // Só negociar nome de campo após rejeição explícita, antes de qualquer trecho.
                                if (!legacyLimit && request.options.maxOutputTokens != null && rejectsCompletionLimit(response)) {
                                    return@use true
                                }
                                val kind = when (response.code) {
                                    401, 403 -> ReasoningError.UNAUTHORIZED
                                    429 -> ReasoningError.RATE_LIMIT
                                    in 500..599 -> ReasoningError.NETWORK
                                    else -> ReasoningError.ENGINE
                                }
                                send(ReasoningEvent.Error(kind, "Custom: HTTP ${response.code}"))
                                return@use false
                            }
                            val source = response.body!!.source()
                            var complete = false
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                if (!line.startsWith("data:")) continue
                                val data = line.substring(5).trim()
                                if (data == "[DONE]") { complete = true; break }
                                val json = try { JSONObject(data) } catch (_: Exception) {
                                    send(ReasoningEvent.Error(ReasoningError.ENGINE, "evento inválido no fluxo Custom"))
                                    return@use false
                                }
                                json.optJSONObject("usage")?.let { usage ->
                                    if (usage.has("prompt_tokens") && !usage.isNull("prompt_tokens")) metrics.inputTokens = usage.optInt("prompt_tokens")
                                    if (usage.has("completion_tokens") && !usage.isNull("completion_tokens")) metrics.outputTokens = usage.optInt("completion_tokens")
                                }
                                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                                val delta = choice?.optJSONObject("delta")
                                val text = if (delta == null || delta.isNull("content")) "" else delta.optString("content")
                                if (text.isNotEmpty()) send(ReasoningEvent.Delta(text))
                            }
                            if (complete) { send(metrics.event()); send(ReasoningEvent.Done) }
                            else send(ReasoningEvent.Error(ReasoningError.NETWORK, "o fluxo Custom terminou antes de [DONE]"))
                            false
                        }
                        if (!retryLegacy) break
                        legacyLimit = true
                        call = client.newCall(firstCall.request().newBuilder()
                            .post(bodyOf(request, supported, LEGACY_LIMIT).toRequestBody("application/json".toMediaType())).build())
                    }
                } catch (e: IOException) {
                    if (activeCall.get().isCanceled()) throw CancellationException("cancelado")
                    send(ReasoningEvent.Error(ReasoningError.NETWORK, "sem conexão com o endpoint Custom"))
                }
            }
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    private fun rejectsCompletionLimit(response: Response): Boolean {
        if (response.code != 400) return false
        // O corpo é não confiável: inspeção limitada e nunca incluída em log ou mensagem para a pessoa.
        val error = try { JSONObject(response.peekBody(8_192L).string()).optJSONObject("error") }
            catch (_: Exception) { null } ?: return false
        val param = error.optString("param").takeIf { it.isNotBlank() && it != "null" }
        if (param != null && param != MODERN_LIMIT) return false
        val code = error.optString("code").lowercase(Locale.ROOT)
        if (code in setOf("invalid_value", "invalid_type")) return false
        val message = error.optString("message").lowercase(Locale.ROOT)
        val unknown = code in setOf("unsupported_parameter", "unknown_parameter", "unrecognized_parameter", "extra_forbidden") ||
            listOf("unknown parameter", "unknown argument", "unrecognized parameter", "unrecognized argument",
                "unrecognized request argument", "unsupported parameter", "unsupported argument").any { it in message }
        val namesLimit = param == MODERN_LIMIT || MODERN_LIMIT in message.split(Regex("[^a-z0-9_]+"))
        return unknown && namesLimit
    }

    private fun bodyOf(request: ReasoningRequest, supported: Set<String>, limitField: String = MODERN_LIMIT): String = JSONObject().apply {
        put("model", model)
        put("messages", JSONArray().apply {
            request.system?.let { put(JSONObject().put("role", "system").put("content", it)) }
            put(JSONObject().put("role", "user").put("content", request.user))
        })
        put("stream", true)
        val o = request.options
        if (GenerationOptions.TEMPERATURE in supported) o.temperature?.let { put("temperature", it) }
        if (GenerationOptions.TOP_P in supported) o.topP?.let { put("top_p", it) }
        if (GenerationOptions.SEED in supported) o.seed?.let { put("seed", it) }
        if (GenerationOptions.MAX_OUTPUT_TOKENS in supported) o.maxOutputTokens?.let { put(limitField, it) }
        if (!o.isDefault) put("stream_options", JSONObject().put("include_usage", true))
    }.toString()

    private companion object {
        const val MODERN_LIMIT = "max_completion_tokens"
        const val LEGACY_LIMIT = "max_tokens"
    }

}
