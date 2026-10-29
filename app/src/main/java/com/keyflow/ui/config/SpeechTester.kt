package com.keyflow.ui.config

import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.speech.SpeechAvailability
import com.keyflow.speech.SpeechDeps
import com.keyflow.speech.SpeechEngine
import com.keyflow.speech.SpeechEngines
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechPlayer
import com.keyflow.speech.SpeechRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * O que o Testar da aba Voz usa fora da tela (FENG-01/04): o motor de uma origem **para um instantâneo de [Settings]**, o que toca o áudio e
 * o atalho para instalar os dados de voz do sistema. A tela não monta motor nem `AudioTrack`; os testes injetam falsos.
 */
class SpeechTester(
    val engineFor: (Settings) -> SpeechEngine,
    val newPlayer: () -> SpeechPlayer,
    /** Nulo = sem atalho (a galeria, os testes). */
    val installTtsData: (() -> Unit)? = null,
) {
    companion object {
        /** Sem motor: Testar emite `NOT_READY`. É o que a galeria e as outras telas usam. */
        val None = SpeechTester(
            engineFor = {
                object : SpeechEngine {
                    override fun speak(request: SpeechRequest): Flow<SpeechEvent> =
                        flowOf(SpeechEvent.Error(SpeechError.NOT_READY, tr("A voz ainda não está disponível nesta versão.")))
                }
            },
            newPlayer = {
                object : SpeechPlayer {
                    override suspend fun write(pcm: ByteArray, sampleRate: Int) = Unit
                    override suspend fun finish() = Unit
                    override fun stop() = Unit
                }
            },
        )

        /** O Testar de verdade: [SpeechEngines.forSource] sobre o ambiente do app. */
        fun of(deps: SpeechDeps, newPlayer: () -> SpeechPlayer, installTtsData: (() -> Unit)? = null) =
            SpeechTester({ SpeechEngines.forSource(it, deps) }, newPlayer, installTtsData)
    }
}
