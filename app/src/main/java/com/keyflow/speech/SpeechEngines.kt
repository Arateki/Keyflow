package com.keyflow.speech

import com.keyflow.core.FalaSource
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.speech.cloud.CustomSpeechEngine
import com.keyflow.speech.cloud.ElevenLabsSpeechEngine
import com.keyflow.speech.cloud.GoogleSpeechEngine
import com.keyflow.speech.cloud.OpenAiSpeechEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient

/** O que falta para a origem escolhida falar. A tela traduz em "o que fazer" (FENG-05/06); [READY] liga o Testar. */
enum class SpeechAvailability { READY, NO_LOCAL_MODEL, MODEL_NOT_INSTALLED, NOT_CONNECTED, NO_ENDPOINT, NO_VOICE }

/**
 * O que é do ambiente e não das `Settings`: o cofre das chaves, o HTTP, os motores locais e do Sistema e o relógio. Tudo entra por lambda para
 * [SpeechEngines] testar na JVM. [apiKey] devolve a chave daquele provedor (nulo/vazio = não conectado); a leitura é do instante da chamada.
 */
class SpeechDeps(
    val http: OkHttpClient,
    val apiKey: (Provider) -> String?,
    val isModelInstalled: (modelId: String) -> Boolean = { false },
    val local: (modelId: String) -> SpeechEngine? = { null },
    val system: () -> SpeechEngine? = { null },
    val decoder: com.keyflow.speech.cloud.CompressedAudioDecoder = com.keyflow.speech.cloud.CompressedAudioDecoder.Unsupported,
    val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class Provider { OPENAI, ELEVENLABS, GOOGLE }
}

/** Escolhe o motor da origem GRAVADA nas [Settings] recebidas — quem chama passa um instantâneo (a tela, o toque; um fluxo, o passo). */
object SpeechEngines {
    const val OPENAI_MODEL = "gpt-4o-mini-tts"

    fun availability(settings: Settings, deps: SpeechDeps): SpeechAvailability =
        availability(settings, { !deps.apiKey(it).isNullOrBlank() }, deps.isModelInstalled)

    /** [hasKey] e [isModelInstalled] entram por lambda: a tela sabe da chave pelos fluxos do cofre, sem decifrar nada na composição. */
    fun availability(
        settings: Settings,
        hasKey: (SpeechDeps.Provider) -> Boolean,
        isModelInstalled: (modelId: String) -> Boolean,
    ): SpeechAvailability = when (settings.falaSource) {
        FalaSource.CREDITS -> SpeechAvailability.NOT_CONNECTED
        FalaSource.LOCAL -> {
            val id = settings.falaLocalModelId
            when {
                id == null -> SpeechAvailability.NO_LOCAL_MODEL
                !isModelInstalled(id) -> SpeechAvailability.MODEL_NOT_INSTALLED
                else -> SpeechAvailability.READY
            }
        }
        FalaSource.SYSTEM -> SpeechAvailability.READY
        FalaSource.OPENAI -> if (hasKey(SpeechDeps.Provider.OPENAI)) SpeechAvailability.READY else SpeechAvailability.NOT_CONNECTED
        FalaSource.ELEVENLABS -> when {
            !hasKey(SpeechDeps.Provider.ELEVENLABS) -> SpeechAvailability.NOT_CONNECTED
            !ElevenLabsSpeechEngine.isVoiceId(settings.falaElevenLabsVoice) -> SpeechAvailability.NO_VOICE
            else -> SpeechAvailability.READY
        }
        FalaSource.GOOGLE -> if (hasKey(SpeechDeps.Provider.GOOGLE)) SpeechAvailability.READY else SpeechAvailability.NOT_CONNECTED
        FalaSource.CUSTOM -> if (settings.falaCustomEndpoint.isBlank()) SpeechAvailability.NO_ENDPOINT else SpeechAvailability.READY
    }

    fun forSource(settings: Settings, deps: SpeechDeps): SpeechEngine = when (settings.falaSource) {
        FalaSource.CREDITS -> failing(SpeechError.NOT_READY, tr("Esta origem ainda não está disponível."))
        FalaSource.LOCAL -> {
            val id = settings.falaLocalModelId
            when {
                id == null || !deps.isModelInstalled(id) ->
                    failing(SpeechError.MODEL_MISSING, tr("Baixe e selecione um modelo para testar."))
                else -> deps.local(id) ?: failing(SpeechError.NOT_READY, tr("A voz no aparelho ainda não está disponível nesta versão."))
            }
        }
        FalaSource.SYSTEM -> deps.system() ?: failing(SpeechError.NOT_READY, tr("A voz do sistema não está disponível."))
        FalaSource.OPENAI -> OpenAiSpeechEngine(
            http = deps.http, apiKey = { deps.apiKey(SpeechDeps.Provider.OPENAI) },
            model = OPENAI_MODEL, voice = settings.falaCloudVoice.ifBlank { "alloy" }, nowMs = deps.nowMs,
        )
        FalaSource.ELEVENLABS -> ElevenLabsSpeechEngine(
            http = deps.http, apiKey = { deps.apiKey(SpeechDeps.Provider.ELEVENLABS) },
            voiceId = settings.falaElevenLabsVoice, model = settings.falaElevenLabsModel.ifBlank { "eleven_multilingual_v2" },
            nowMs = deps.nowMs,
        )
        FalaSource.GOOGLE -> GoogleSpeechEngine(
            http = deps.http, apiKey = { deps.apiKey(SpeechDeps.Provider.GOOGLE) },
            voice = settings.falaGoogleVoice.ifBlank { "pt-BR-Neural2-A" }, nowMs = deps.nowMs,
        )
        FalaSource.CUSTOM -> CustomSpeechEngine(
            http = deps.http, endpoint = settings.falaCustomEndpoint.trim(),
            headerName = settings.falaCustomHeaderName.trim(), headerValue = settings.falaCustomHeaderValue,
            model = settings.falaCustomModel, voice = settings.falaCustomVoice,
            format = SpeechFormat.of(settings.falaCustomFormat), decoder = deps.decoder, nowMs = deps.nowMs,
        )
    }

    private fun failing(kind: SpeechError, message: String): SpeechEngine = object : SpeechEngine {
        override fun speak(request: SpeechRequest): Flow<SpeechEvent> = flowOf(SpeechEvent.Error(kind, message))
    }
}
