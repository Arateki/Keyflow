package com.keyflow.reasoning.custom

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
        val call = try {
            val customHeaders = headers()
            client.newCall(Request.Builder().url(endpoint)
                .apply { customHeaders.forEach { (name, value) -> header(name, value) } }
                .header("Accept", "text/event-stream").post(body).build())
        } catch (_: IllegalArgumentException) {
            send(ReasoningEvent.Error(ReasoningError.ENGINE, "endpoint ou cabeçalho Custom inválido"))
            return@channelFlow
        }
        var finished = false
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } catch (e: CancellationException) {
                if (!finished) call.cancel()
                throw e
            }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val kind = when (response.code) {
                                401, 403 -> ReasoningError.UNAUTHORIZED
                                429 -> ReasoningError.RATE_LIMIT
                                in 500..599 -> ReasoningError.NETWORK
                                else -> ReasoningError.ENGINE
                            }
                            send(ReasoningEvent.Error(kind, "Custom: HTTP ${response.code}"))
                            return@use
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
                                return@use
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
                    }
                } catch (e: IOException) {
                    if (call.isCanceled()) throw CancellationException("cancelado")
                    send(ReasoningEvent.Error(ReasoningError.NETWORK, "sem conexão com o endpoint Custom"))
                }
            }
        } finally {
            finished = true
            watcher.cancel()
        }
    }

    private fun bodyOf(request: ReasoningRequest, supported: Set<String>): String = JSONObject().apply {
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
        if (GenerationOptions.MAX_OUTPUT_TOKENS in supported) o.maxOutputTokens?.let { put("max_completion_tokens", it) }
        if (!o.isDefault) put("stream_options", JSONObject().put("include_usage", true))
    }.toString()
}
