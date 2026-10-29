package com.keyflow.speech.cloud

import com.keyflow.speech.SpeechEngine
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechRequest
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Google Cloud Text-to-Speech (FCLD-04), não o Gemini. Doc de 2026-10-29: `POST https://texttospeech.googleapis.com/v1/text:synthesize`,
 * corpo `input.text`, `voice.{languageCode,name}` e `audioConfig.{audioEncoding=LINEAR16,sampleRateHertz,speakingRate 0,25–2,0}`; a resposta
 * traz `audioContent` em base64 e, em LINEAR16, o cabeçalho WAV. A chave vai no cabeçalho `X-Goog-Api-Key`.
 */
class GoogleSpeechEngine(
    http: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val voice: String,
    private val baseUrl: String = "https://texttospeech.googleapis.com",
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : SpeechEngine {
    private val cloud = CloudSpeech(http, "Google", nowMs)

    override fun speak(request: SpeechRequest): Flow<SpeechEvent> = cloud.run(
        build = {
            val key = apiKey()?.trim().orEmpty()
            if (key.isEmpty()) return@run missingKey()
            val body = JSONObject().apply {
                put("input", JSONObject().put("text", request.text))
                put("voice", JSONObject().put("languageCode", languageOf(voice)).put("name", voice))
                put(
                    "audioConfig",
                    JSONObject().put("audioEncoding", "LINEAR16").put("sampleRateHertz", RATE)
                        .put("speakingRate", request.speed.clampTo(0.25f, 2.0f).toDouble()),
                )
            }.toString()
            try {
                BuiltRequest.Ready(
                    Request.Builder().url("$baseUrl/v1/text:synthesize")
                        .header("X-Goog-Api-Key", key)
                        .post(body.toRequestBody(JSON))
                        .build(),
                )
            } catch (e: IllegalArgumentException) {
                badCredentials()
            }
        },
        body = AudioBody.GoogleJson,
    )

    companion object {
        private const val RATE = 24_000

        /** `pt-BR-Neural2-A` → `pt-BR`. Voz sem prefixo de idioma (as Journey antigas) cai em `en-US`. */
        fun languageOf(voice: String): String {
            val parts = voice.split('-')
            return if (parts.size >= 3 && parts[0].length == 2) "${parts[0]}-${parts[1]}" else "en-US"
        }
    }
}
