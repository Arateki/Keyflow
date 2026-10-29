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
 * ElevenLabs (FCLD-03). Doc oficial de 2026-10-29 (`/docs/api-reference/text-to-speech/stream`): `POST /v1/text-to-speech/{voice_id}/stream`,
 * header `xi-api-key`, query `output_format=pcm_24000` (PCM 16-bit mono, em fluxo), corpo `text`, `model_id` e `voice_settings.speed`.
 * A doc não dá intervalo para a velocidade: o app limita a 0,7–1,2, o que a API aceita na prática.
 */
class ElevenLabsSpeechEngine(
    http: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val voiceId: String,
    private val model: String,
    private val baseUrl: String = "https://api.elevenlabs.io",
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : SpeechEngine {
    private val cloud = CloudSpeech(http, "ElevenLabs", nowMs)

    override fun speak(request: SpeechRequest): Flow<SpeechEvent> = cloud.run(
        build = {
            val key = apiKey()?.trim().orEmpty()
            if (key.isEmpty()) return@run missingKey()
            val body = JSONObject().apply {
                put("text", request.text)
                put("model_id", model)
                put("voice_settings", JSONObject().put("speed", request.speed.clampTo(0.7f, 1.2f).toDouble()))
            }.toString()
            try {
                BuiltRequest.Ready(
                    Request.Builder().url("$baseUrl/v1/text-to-speech/$voiceId/stream?output_format=pcm_$RATE")
                        .header("xi-api-key", key)
                        .post(body.toRequestBody(JSON))
                        .build(),
                )
            } catch (e: IllegalArgumentException) {
                badCredentials()
            }
        },
        body = AudioBody.RawPcm(RATE),
    )

    companion object {
        private const val RATE = 24_000

        /** O `voice_id` da ElevenLabs: 20 letras e dígitos. Um nome ("Rachel", valor antigo) não serve de id. */
        fun isVoiceId(value: String): Boolean = value.length == 20 && value.all { it.isLetterOrDigit() && it.code < 128 }
    }
}
