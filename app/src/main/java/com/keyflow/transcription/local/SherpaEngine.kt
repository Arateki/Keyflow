package com.keyflow.transcription.local

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.keyflow.data.models.EngineType

/**
 * Configs do sherpa-onnx por [EngineType] — conferidas no `kotlin-api/OfflineRecognizer.kt` oficial
 * (v1.13.8): Whisper com `modelType = "whisper"`; Parakeet TDT v3 com `modelType = "nemo_transducer"`.
 * Idioma vazio no Whisper multilíngue = detecção automática.
 */
object SherpaConfigs {

    fun recognizerConfig(model: EngineModel, numThreads: Int): OfflineRecognizerConfig {
        val modelConfig = when (model.engineType) {
            EngineType.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = model.encoder,
                    decoder = model.decoder,
                    language = model.language.orEmpty(),
                    task = "transcribe",
                ),
                tokens = model.tokens,
                modelType = "whisper",
                numThreads = numThreads,
            )
            // No catálogo, TRANSDUCER é o Parakeet (NeMo).
            EngineType.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = model.encoder,
                    decoder = model.decoder,
                    joiner = requireNotNull(model.joiner) { "${model.modelId}: transducer sem joiner" },
                ),
                tokens = model.tokens,
                modelType = "nemo_transducer",
                numThreads = numThreads,
            )
            // Modelos de texto, visão e voz (TTS) não passam por aqui: a transcrição só recebe o que sai de `ModelCatalog.SPEECH`.
            EngineType.LITERT_LM -> error("${model.modelId}: motor de texto (LiteRT-LM) não roda no sherpa-onnx")
            EngineType.OCR -> error("${model.modelId}: motor de visão (OCR) não roda no sherpa-onnx")
            EngineType.TTS -> error("${model.modelId}: motor de voz (TTS) não roda no reconhecedor de fala")
        }
        return OfflineRecognizerConfig(modelConfig = modelConfig)
    }

    fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
}

/**
 * Um `OfflineRecognizer` carregado por vez, residente até outro modelo (ou idioma) pedir troca (G10).
 * Só roda no processo `:inference`, sempre na mesma thread do executor do [InferenceService].
 */
class SherpaEngine(private val numThreads: Int = SherpaConfigs.defaultThreads()) {

    private var recognizer: OfflineRecognizer? = null
    private var loaded: EngineModel? = null

    fun isLoaded(model: EngineModel): Boolean = loaded == model && recognizer != null

    /** @throws IllegalArgumentException se o sherpa-onnx recusar a config (arquivo inválido etc.). */
    fun load(model: EngineModel) {
        if (isLoaded(model)) return
        unload()
        recognizer = OfflineRecognizer(config = SherpaConfigs.recognizerConfig(model, numThreads))
        loaded = model
    }

    fun unload() {
        recognizer?.release()
        recognizer = null
        loaded = null
    }

    /** PCM mono float em 16 kHz (saída do [AudioDecoder]) → texto. */
    fun transcribe(samples: FloatArray): String {
        val model = checkNotNull(loaded) { "nenhum modelo carregado" }
        val active = checkNotNull(recognizer)
        val pieces = if (model.engineType == EngineType.WHISPER) WhisperChunker.split(samples) else listOf(samples)
        return pieces.map { decode(active, it) }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AudioDecoder.TARGET_SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }
}
