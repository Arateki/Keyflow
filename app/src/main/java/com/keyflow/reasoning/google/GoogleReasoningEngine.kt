package com.keyflow.reasoning.google

import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.providers.google.GoogleAuthConfig
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
 * Motor de raciocínio para Google Gemini usando a API REST v1beta com streaming SSE.
 * Suporta autenticação tanto via OAuth Bearer token quanto por Google AI Studio API Key (x-goog-api-key).
 */
class GoogleReasoningEngine(
    private val authorization: AuthorizationProvider,
    http: OkHttpClient,
    private val model: String,
    private val apiBaseUrl: String = GoogleAuthConfig.apiBaseUrl,
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
) : ReasoningEngine {

    internal val client: OkHttpClient = http.newBuilder()
        .dns(Ipv4FirstDns())
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = channelFlow {
        val headers = try {
            authorization.headers(ProviderIds.GOOGLE)
        } catch (e: AuthException) {
            send(ReasoningEvent.Error(authError(e.kind), "autenticação Google: ${e.kind}"))
            return@channelFlow
        }

        val body = bodyOf(request).toRequestBody("application/json".toMediaType())
        var attempt = 0

        while (true) {
            val call = try {
                client.newCall(
                    Request.Builder()
                        .url("$apiBaseUrl/models/$model:streamGenerateContent?alt=sse")
                        .apply { headers.forEach { (name, value) -> header(name, value) } }
                        .header("Accept", "text/event-stream")
                        .post(body)
                        .build(),
                )
            } catch (e: IllegalArgumentException) {
                send(ReasoningEvent.Error(ReasoningError.UNAUTHORIZED, "credenciais Google inválidas"))
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
            send(ReasoningEvent.Error(ReasoningError.NETWORK, "sem conexão com o Google Gemini"))
            return Outcome.Finished
        }

        response.use { r ->
            if (!r.isSuccessful) {
                val failure = httpFailure(r.code, r.body?.string().orEmpty())
                if (failure.retry) return Outcome.Retry(failure.event)
                if (r.code == 401) authorization.invalidate(ProviderIds.GOOGLE)
                send(failure.event)
                return Outcome.Finished
            }
            try {
                readStream(r.body!!.source(), send)
            } catch (e: IOException) {
                if (call.isCanceled()) throw CancellationException("cancelado")
                send(ReasoningEvent.Error(ReasoningError.NETWORK, "o fluxo SSE do Gemini foi interrompido"))
            }
        }
        return Outcome.Finished
    }

    private suspend fun readStream(source: BufferedSource, send: suspend (ReasoningEvent) -> Unit) {
        val data = StringBuilder()
        var completed = false

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        val terminal = handleData(data.toString(), send)
                        if (terminal) completed = true
                        data.clear()
                    }
                }
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
        }

        if (data.isNotEmpty()) {
            val terminal = handleData(data.toString(), send)
            if (terminal) completed = true
        }

        if (!completed) {
            send(ReasoningEvent.Done)
        }
    }

    private suspend fun handleData(data: String, send: suspend (ReasoningEvent) -> Unit): Boolean {
        if (data.trim() == "[DONE]") {
            send(ReasoningEvent.Done)
            return true
        }

        val json = try {
            JSONObject(data)
        } catch (e: JSONException) {
            send(ReasoningEvent.Error(ReasoningError.ENGINE, "evento JSON inválido no fluxo Gemini"))
            return true
        }

        val candidates = json.optJSONArray("candidates") ?: return false
        if (candidates.length() == 0) return false

        val firstCandidate = candidates.optJSONObject(0) ?: return false
        val content = firstCandidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")

        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i)
                val text = part?.optString("text").orEmpty()
                if (text.isNotEmpty()) {
                    send(ReasoningEvent.Delta(text))
                }
            }
        }

        val finishReason = firstCandidate.optString("finishReason")
        if (finishReason == "STOP" || finishReason == "MAX_TOKENS") {
            send(ReasoningEvent.Done)
            return true
        }

        return false
    }

    private class HttpFailure(val event: ReasoningEvent.Error, val retry: Boolean)

    private fun httpFailure(status: Int, body: String): HttpFailure {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val errorObj = json?.optJSONObject("error")
        val message = errorObj?.optString("message").orEmpty()
        val note = buildString {
            append("Google Gemini HTTP $status")
            if (message.isNotEmpty()) append(": $message")
        }

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

    private fun bodyOf(request: ReasoningRequest): String = JSONObject().apply {
        val userPart = JSONObject().put("text", request.user)
        val userContent = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(userPart))
        }
        put("contents", JSONArray().put(userContent))

        request.system?.let { sysPrompt ->
            val sysPart = JSONObject().put("text", sysPrompt)
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(sysPart))
            })
        }
    }.toString()

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 200L
        const val READ_TIMEOUT_S = 120L
    }
}
