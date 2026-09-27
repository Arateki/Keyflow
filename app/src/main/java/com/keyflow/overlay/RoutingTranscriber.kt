package com.keyflow.overlay

import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import com.keyflow.data.EffectiveConfig
import java.io.File

/** Transcrição no aparelho (o [com.keyflow.transcription.local.LocalTranscriber] no app; fake nos testes). */
fun interface LocalTranscription {
    suspend fun transcribe(local: EffectiveConfig.Local, audio: File): TranscriptionResult
}

/**
 * Decide o caminho de cada transcrição pela config (NM-T9): origem Local com modelo → processo
 * `:inference`; o resto → o transcriber HTTP de sempre, intocado. Com Local, o HTTP nunca é chamado —
 * nem como fallback de uma falha local (NAT-04, PRV-01).
 */
class RoutingTranscriber(
    private val http: OverlayTranscriber,
    private val local: LocalTranscription,
) : OverlayTranscriber {

    override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult =
        cfg.local?.let { local.transcribe(it, audio) } ?: http.transcribe(audio, cfg)

    /** Só o HTTP é abortável; a inferência local termina e o resultado tardio é descartado pelo coordinator. */
    override fun cancelInFlight() = http.cancelInFlight()
}
