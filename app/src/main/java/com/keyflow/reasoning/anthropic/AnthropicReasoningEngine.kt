package com.keyflow.reasoning.anthropic

import com.keyflow.BuildConfig
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.HttpErrorMessage
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.providers.anthropic.AnthropicAuthConfig
import com.keyflow.data.models.Ipv4FirstDns
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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Motor de raciocínio para Anthropic Claude usando a API v1/messages com streaming SSE.
 */
class AnthropicReasoningEngine(
    private val authorization: AuthorizationProvider,
    http: OkHttpClient,
    private val model: String,
    private val apiBaseUrl: String = AnthropicAuthConfig.apiBaseUrl,
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
) : ReasoningEngine {

    internal val client: OkHttpClient = http.newBuilder()
        .dns(Ipv4FirstDns())
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val resolvedModel: String = when (model) {
        "claude-3-7-sonnet" -> "claude-3-7-sonnet-20250219"
        "claude-3-5-sonnet" -> "claude-3-5-sonnet-20241022"
        "claude-3-5-haiku" -> "claude-3-5-haiku-20241022"
        "claude-3-opus" -> "claude-3-opus-20240229"
        else -> model
    }

    override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = channelFlow {
        val headers = try {
            authorization.headers(ProviderIds.ANTHROPIC)
        } catch (e: AuthException) {
            send(ReasoningEvent.Error(authError(e.kind), "autenticação Anthropic: ${e.kind}"))
            return@channelFlow
        }

        val isOAuth = headers["Authorization"]?.startsWith("Bearer ") == true
        val jsonBody = bodyOf(request, isOAuth)
        // Sem o corpo: é o texto do usuário (ditado, prompt), e o Log.d sai também no release.
        android.util.Log.d("AnthropicReasoning", "Outgoing model: $resolvedModel, isOAuth: $isOAuth, corpo: ${jsonBody.length} caracteres")
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
                send(ReasoningEvent.Error(ReasoningError.UNAUTHORIZED, "credenciais Anthropic inválidas"))
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
        class Retry(val error: ReasoningEvent.Error) : Outcome
    }

    private suspend fun ProducerScope<ReasoningEvent>.attemptOnce(call: Call): Outcome {
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

    private suspend fun execute(call: Call, send: suspend (ReasoningEvent) -> Unit): Outcome {
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (call.isCanceled()) throw CancellationException("cancelado")
            send(ReasoningEvent.Error(ReasoningError.NETWORK, "sem conexão com a Anthropic"))
            return Outcome.Finished
        }

        response.use { r ->
            if (!r.isSuccessful) {
                val errorBody = r.body?.string().orEmpty()
                if (BuildConfig.DEBUG) android.util.Log.e("AnthropicReasoning", "HTTP ${r.code}: $errorBody")
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
                send(ReasoningEvent.Error(ReasoningError.NETWORK, "o fluxo SSE da Anthropic foi interrompido"))
            }
        }
        return Outcome.Finished
    }

    private suspend fun readStream(source: BufferedSource, send: suspend (ReasoningEvent) -> Unit) {
        val data = StringBuilder()
        var eventName: String? = null
        var completed = false

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        val terminal = handleEvent(eventName, data.toString(), send)
                        if (terminal) completed = true
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

        if (data.isNotEmpty()) {
            val terminal = handleEvent(eventName, data.toString(), send)
            if (terminal) completed = true
        }

        if (!completed) {
            send(ReasoningEvent.Done)
        }
    }

    private suspend fun handleEvent(eventName: String?, data: String, send: suspend (ReasoningEvent) -> Unit): Boolean {
        val json = try {
            JSONObject(data)
        } catch (e: JSONException) {
            return false
        }

        val type = json.optString("type").ifEmpty { eventName.orEmpty() }
        when (type) {
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta")
                val text = delta?.optString("text").orEmpty()
                if (text.isNotEmpty()) {
                    send(ReasoningEvent.Delta(text))
                }
            }
            "message_stop" -> {
                send(ReasoningEvent.Done)
                return true
            }
            "error" -> {
                if (BuildConfig.DEBUG) android.util.Log.e("AnthropicReasoning", "SSE error recebido")
                send(ReasoningEvent.Error(ReasoningError.ENGINE, "erro no fluxo SSE da Anthropic"))
                return true
            }
        }
        return false
    }

    private class HttpFailure(val event: ReasoningEvent.Error, val retry: Boolean)

    private fun httpFailure(status: Int, body: String): HttpFailure {
        val note = HttpErrorMessage.of("Anthropic", status, body, includeDebugDetail = false)

        val kind = when {
            status == 401 || status == 403 -> ReasoningError.UNAUTHORIZED
            status == 429 -> ReasoningError.RATE_LIMIT
            status >= 500 -> ReasoningError.NETWORK
            else -> ReasoningError.ENGINE
        }
        return HttpFailure(ReasoningEvent.Error(kind, note), retry = status >= 500)
    }

    private fun authError(kind: AuthErrorKind): ReasoningError = when (kind) {
        AuthErrorKind.NETWORK, AuthErrorKind.TIMEOUT -> ReasoningError.NETWORK
        else -> ReasoningError.UNAUTHORIZED
    }

    private fun bodyOf(request: ReasoningRequest, isOAuth: Boolean = false): String = JSONObject().apply {
        put("model", resolvedModel)
        put("max_tokens", 1024)
        put("stream", true)
        val msg = JSONObject().put("role", "user").put("content", request.user)
        put("messages", JSONArray().put(msg))
        val systemPrompt = if (isOAuth) {
            val prefix = "You are Claude Code, Anthropic's official CLI for Claude."
            if (request.system.isNullOrBlank()) prefix else "$prefix\n\n${request.system}"
        } else {
            request.system
        }
        systemPrompt?.let { put("system", it) }
    }.toString()

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 200L
        const val READ_TIMEOUT_S = 120L
    }
}
