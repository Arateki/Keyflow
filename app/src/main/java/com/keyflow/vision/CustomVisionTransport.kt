package com.keyflow.vision

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
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI Chat Completions com imagem, somente para o Custom escolhido. Sem log de corpo, imagem ou cabeçalho. */
class CustomVisionTransport(
    http: OkHttpClient,
    private val endpoint: String,
    private val model: String,
    private val headerName: String,
    private val headerValue: String,
) : ReasoningEngine {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    override fun generate(request: ReasoningRequest) = channelFlow<ReasoningEvent> {
        val call = try {
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", request.user))
            request.image?.let { content.put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", it.dataUrl()))) }
            val body = JSONObject().put("model", model).put("stream", true)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            val original = endpoint.toHttpUrl()
            val url = if (original.encodedPath.trimEnd('/').endsWith("/chat/completions")) original
                else original.newBuilder().addPathSegments("chat/completions").build()
            client.newCall(Request.Builder().url(url).apply {
                if (headerName.isNotBlank() && headerValue.isNotBlank()) header(headerName, headerValue)
            }.header("Accept", "text/event-stream").post(body.toString().toRequestBody("application/json".toMediaType())).build())
        } catch (_: IllegalArgumentException) {
            send(ReasoningEvent.Error(ReasoningError.ENGINE, null)); return@channelFlow
        }
        var finished = false
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { if (!finished) call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val kind = when (response.code) {
                            401, 403 -> ReasoningError.UNAUTHORIZED
                            429 -> ReasoningError.RATE_LIMIT
                            in 500..599 -> ReasoningError.NETWORK
                            else -> ReasoningError.ENGINE
                        }
                        send(ReasoningEvent.Error(kind, null)); return@withContext
                    }
                    val source = response.body?.source() ?: error("resposta vazia")
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.substring(5).trim()
                        if (data == "[DONE]") { send(ReasoningEvent.Done); return@withContext }
                        val json = JSONObject(data)
                        if (json.has("error")) { send(ReasoningEvent.Error(ReasoningError.ENGINE, null)); return@withContext }
                        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
                        choice.optJSONObject("delta")?.optString("content")?.takeIf { it.isNotEmpty() }
                            ?.let { send(ReasoningEvent.Delta(it)) }
                        val finish = choice.optString("finish_reason", "")
                        if (finish.isNotEmpty() && finish != "null") {
                            if (finish != "stop") { send(ReasoningEvent.Error(ReasoningError.ENGINE, null)); return@withContext }
                            // O marcador [DONE] fecha o fluxo; fim de arquivo sem ele é resposta interrompida.
                        }
                    }
                    send(ReasoningEvent.Error(ReasoningError.NETWORK, null))
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: IOException) {
            if (call.isCanceled()) throw CancellationException("cancelado")
            send(ReasoningEvent.Error(ReasoningError.NETWORK, null))
        } catch (_: Exception) { send(ReasoningEvent.Error(ReasoningError.ENGINE, null))
        } finally { finished = true; watcher.cancel() }
    }
}
