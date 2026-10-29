package com.keyflow.speech.cloud

import com.keyflow.i18n.tr
import com.keyflow.speech.SpeechEngine
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechFormat
import com.keyflow.speech.SpeechRequest
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** O JSON do esquema `POST /v1/audio/speech`, igual na OpenAI e no Custom. */
internal fun speechJson(model: String, text: String, voice: String, format: SpeechFormat, speed: Float): String =
    JSONObject().apply {
        if (model.isNotBlank()) put("model", model)
        put("input", text)
        if (voice.isNotBlank()) put("voice", voice)
        put("response_format", format.wire)
        put("speed", speed.clampTo(0.25f, 4.0f).toDouble())
    }.toString()

/** Os formatos do esquema OpenAI e como cada um é lido: `pcm` cru em 24 kHz, `wav` pelo cabeçalho e o resto por `MediaCodec`. */
internal fun audioBodyFor(format: SpeechFormat, decoder: CompressedAudioDecoder): AudioBody = when (format) {
    SpeechFormat.PCM -> AudioBody.RawPcm(OPENAI_PCM_RATE)
    SpeechFormat.WAV -> AudioBody.Wav
    else -> AudioBody.Compressed(format, decoder)
}

internal const val OPENAI_PCM_RATE = 24_000

/**
 * OpenAI Text-to-speech (FCLD-02): `POST https://api.openai.com/v1/audio/speech` com Bearer da chave conectada, `response_format=pcm`
 * (24 kHz, 16-bit, mono). Não lê cota. O modelo atual e os `tts-1*` deixam de funcionar em 2027-01-06 (aviso no card).
 */
class OpenAiSpeechEngine(
    http: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val model: String,
    private val voice: String,
    private val baseUrl: String = "https://api.openai.com",
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : SpeechEngine {
    private val cloud = CloudSpeech(http, "OpenAI", nowMs)

    override fun speak(request: SpeechRequest): Flow<SpeechEvent> = cloud.run(
        build = {
            val key = apiKey()?.trim().orEmpty()
            if (key.isEmpty()) return@run missingKey()
            try {
                BuiltRequest.Ready(
                    Request.Builder().url("$baseUrl/v1/audio/speech")
                        .header("Authorization", "Bearer $key")
                        .post(speechJson(model, request.text, voice, SpeechFormat.PCM, request.speed).toRequestBody(JSON))
                        .build(),
                )
            } catch (e: IllegalArgumentException) {
                badCredentials()
            }
        },
        body = AudioBody.RawPcm(OPENAI_PCM_RATE),
    )
}

internal val JSON = "application/json".toMediaType()

/**
 * Custom (FCLD-05): o esquema OpenAI na URL completa que o usuário colar, com o header gravado. Uma resposta que não seja áudio no formato
 * escolhido falha com [SpeechError.ENGINE]. Ex.: `https://api.groq.com/openai/v1/audio/speech` (inglês e árabe).
 */
class CustomSpeechEngine(
    http: OkHttpClient,
    private val endpoint: String,
    private val headerName: String,
    private val headerValue: String,
    private val model: String,
    private val voice: String,
    private val format: SpeechFormat,
    private val decoder: CompressedAudioDecoder,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : SpeechEngine {
    private val cloud = CloudSpeech(http, "Custom", nowMs)

    override fun speak(request: SpeechRequest): Flow<SpeechEvent> = cloud.run(
        build = {
            try {
                BuiltRequest.Ready(
                    Request.Builder().url(endpoint)
                        .apply { if (headerName.isNotBlank() && headerValue.isNotBlank()) header(headerName, headerValue) }
                        .post(speechJson(model, request.text, voice, format, request.speed).toRequestBody(JSON))
                        .build(),
                )
            } catch (e: IllegalArgumentException) {
                BuiltRequest.Failed(SpeechEvent.Error(SpeechError.ENGINE, tr("O endpoint ou o header não é válido.")))
            }
        },
        body = audioBodyFor(format, decoder),
    )
}
