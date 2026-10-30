package com.keyflow.overlay

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.core.ErrorKind
import com.keyflow.core.InsertOutcome
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import com.keyflow.fluxos.execucao.CausaErro
import com.keyflow.fluxos.execucao.DestinoFluxo
import com.keyflow.fluxos.execucao.ResultadoDitado
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * O gancho do ditado de fluxo no `OverlayCoordinator` (FLX-18, design §7.1): com destino, o texto vai ao fluxo e não ao campo; o
 * destino é solto **uma vez** em todo fim da sessão; sem destino nada muda (a suíte de `OverlayCoordinatorTest` é a prova).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DitadoDeFluxoTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private class Recorder(private val audio: File?) : OverlayAudioRecorder {
        var started = 0
        var discarded = 0
        override fun start() { started++ }
        override fun stop(): File? = audio
        override fun discard() { discarded++ }
    }

    private class Transcriber(var result: TranscriptionResult) : OverlayTranscriber {
        val configs = mutableListOf<Config>()
        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            configs += cfg
            return result
        }
    }

    private class Ambiente(scope: CoroutineScope, audio: File?, resultado: TranscriptionResult, clock: () -> Long, microfone: Boolean = true) {
        val recorder = Recorder(audio)
        val transcriber = Transcriber(resultado)
        val inseridos = mutableListOf<String>()
        val clipboard = mutableListOf<String>()
        val erros = mutableListOf<String>()
        val estados = mutableListOf<ButtonState>()
        val abaConfig = Config(endpointUrl = "https://aba.test/v1", authHeaderValue = "Bearer aba")
        val coordinator = OverlayCoordinator(
            scope = scope,
            configProvider = { abaConfig },
            recorder = recorder,
            transcriber = transcriber,
            insertOrHold = { inseridos += it; InsertOutcome.Inserted },
            flushPending = { false },
            targetAvailable = { true },
            render = { estados += it },
            onClipboard = { clipboard += it },
            onError = { mensagem, _ -> erros += mensagem },
            recordingClockMs = clock,
            microphoneGranted = { microfone },
            cancelWindowMs = { 3000L },
        )
    }

    private class Destino(val config: (suspend () -> Config)? = null) {
        val resultados = mutableListOf<ResultadoDitado>()
        val destino = DestinoFluxo(config) { resultados += it }
    }

    private fun audio(nome: String) = pasta.newFile(nome).also { it.writeText("audio") }

    @Test
    fun `with a destination the text reaches it and neither insertOrHold nor the clipboard is used`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, audio("a.m4a"), TranscriptionResult.Success("olá fluxo"), { agora })
        val destino = Destino()
        assertThat(amb.coordinator.iniciarDitadoDeFluxo(destino.destino)).isTrue()
        assertThat(amb.coordinator.currentState()).isEqualTo(ButtonState.RECORDING)
        assertThat(amb.coordinator.ditadoDeFluxo).isTrue()
        agora = 2_000
        amb.coordinator.onTap() // o toque na orbe envia
        advanceUntilIdle()
        assertThat(destino.resultados).containsExactly(ResultadoDitado.Texto("olá fluxo"))
        assertThat(amb.inseridos).isEmpty()
        assertThat(amb.clipboard).isEmpty()
        assertThat(amb.erros).isEmpty()
        assertThat(amb.coordinator.currentState()).isEqualTo(ButtonState.IDLE_READY)
        assertThat(amb.coordinator.ditadoDeFluxo).isFalse()
    }

    @Test
    fun `the destination is released after the machine is back at rest so the next step sees an idle state`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, audio("b.m4a"), TranscriptionResult.Success("x"), { agora })
        var estadoNaEntrega: ButtonState? = null
        amb.coordinator.iniciarDitadoDeFluxo(DestinoFluxo(null) { estadoNaEntrega = amb.coordinator.currentState() })
        agora = 2_000
        amb.coordinator.onTap()
        advanceUntilIdle()
        assertThat(estadoNaEntrega).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `a transcription error reaches the destination as a failure without the orb banner`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, audio("c.m4a"), TranscriptionResult.Error(ErrorKind.NETWORK, "offline"), { agora })
        val destino = Destino()
        amb.coordinator.iniciarDitadoDeFluxo(destino.destino)
        agora = 2_000
        amb.coordinator.onTap()
        advanceUntilIdle()
        val falha = destino.resultados.single() as ResultadoDitado.Falha
        assertThat(falha.erro.causa).isEqualTo(CausaErro.REDE)
        assertThat(amb.erros).isEmpty() // o aviso é do fluxo
        assertThat(amb.inseridos).isEmpty()
    }

    @Test
    fun `the destination config replaces the tab config for the transcription`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, audio("d.m4a"), TranscriptionResult.Success("ok"), { agora })
        val daEtapa = Config(endpointUrl = "https://etapa.test/v1", authHeaderValue = "Bearer etapa")
        amb.coordinator.iniciarDitadoDeFluxo(DestinoFluxo({ daEtapa }) {})
        agora = 2_000
        amb.coordinator.onTap()
        advanceUntilIdle()
        assertThat(amb.transcriber.configs).containsExactly(daEtapa)
    }

    @Test
    fun `without a destination config the tab config is used`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, audio("e.m4a"), TranscriptionResult.Success("ok"), { agora })
        amb.coordinator.iniciarDitadoDeFluxo(DestinoFluxo(null) {})
        agora = 2_000
        amb.coordinator.onTap()
        advanceUntilIdle()
        assertThat(amb.transcriber.configs).containsExactly(amb.abaConfig)
    }

    @Test
    fun `a recording that is too short releases the destination as an empty failure`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, audio("f.m4a"), TranscriptionResult.Success("x"), { agora })
        val destino = Destino()
        amb.coordinator.iniciarDitadoDeFluxo(destino.destino)
        agora = 100
        amb.coordinator.onTap()
        runCurrent()
        val falha = destino.resultados.single() as ResultadoDitado.Falha
        assertThat(falha.erro.causa).isEqualTo(CausaErro.VAZIO)
        assertThat(amb.erros).isEmpty()
        assertThat(amb.coordinator.currentState()).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `a recorder that returns no file releases the destination as an engine failure`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, null, TranscriptionResult.Success("x"), { agora })
        val destino = Destino()
        amb.coordinator.iniciarDitadoDeFluxo(destino.destino)
        agora = 2_000
        amb.coordinator.onTap()
        runCurrent()
        assertThat((destino.resultados.single() as ResultadoDitado.Falha).erro.causa).isEqualTo(CausaErro.MOTOR)
        assertThat(amb.coordinator.ditadoDeFluxo).isFalse()
    }

    @Test
    fun `a finished cancellation releases the destination as cancelled and undo does not`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, audio("g.m4a"), TranscriptionResult.Success("x"), { agora })
        val destino = Destino()
        amb.coordinator.iniciarDitadoDeFluxo(destino.destino)
        agora = 2_000
        amb.coordinator.onCancel()
        assertThat(amb.coordinator.currentState()).isEqualTo(ButtonState.CANCELLING)
        assertThat(destino.resultados).isEmpty()
        amb.coordinator.onUndoCancel() // desfazer: a sessão continua, o destino também
        assertThat(destino.resultados).isEmpty()
        assertThat(amb.coordinator.ditadoDeFluxo).isTrue()
        amb.coordinator.onCancel()
        advanceTimeBy(3_100)
        runCurrent()
        assertThat(destino.resultados).containsExactly(ResultadoDitado.Cancelado)
        assertThat(amb.coordinator.currentState()).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `committing a cancellation releases the destination at once and only once`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, audio("h.m4a"), TranscriptionResult.Success("x"), { agora })
        val destino = Destino()
        amb.coordinator.iniciarDitadoDeFluxo(destino.destino)
        agora = 2_000
        amb.coordinator.onCancel()
        amb.coordinator.onCommitCancel()
        amb.coordinator.onCommitCancel()
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(destino.resultados).containsExactly(ResultadoDitado.Cancelado)
    }

    @Test
    fun `missing microphone permission releases the destination as unavailable`() = runTest {
        val amb = Ambiente(this, null, TranscriptionResult.Success("x"), { 0L }, microfone = false)
        val destino = Destino()
        assertThat(amb.coordinator.iniciarDitadoDeFluxo(destino.destino)).isTrue() // aceitou; a falha chega ao destino
        runCurrent()
        assertThat((destino.resultados.single() as ResultadoDitado.Falha).erro.causa).isEqualTo(CausaErro.INDISPONIVEL)
        assertThat(amb.recorder.started).isEqualTo(0)
    }

    @Test
    fun `starting is refused outside idle and with another destination active`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, audio("i.m4a"), TranscriptionResult.Success("x"), { agora })
        amb.coordinator.onTap() // gravação comum
        assertThat(amb.coordinator.currentState()).isEqualTo(ButtonState.RECORDING)
        val recusado = Destino()
        assertThat(amb.coordinator.iniciarDitadoDeFluxo(recusado.destino)).isFalse()
        assertThat(amb.coordinator.ditadoDeFluxo).isFalse()
        agora = 2_000
        amb.coordinator.onTap()
        advanceUntilIdle()
        assertThat(amb.inseridos).containsExactly("x") // o ditado comum de sempre segue
    }

    @Test
    fun `a second destination is refused while the first is active`() = runTest {
        val amb = Ambiente(this, audio("j.m4a"), TranscriptionResult.Success("x"), { 0L })
        assertThat(amb.coordinator.iniciarDitadoDeFluxo(Destino().destino)).isTrue()
        assertThat(amb.coordinator.iniciarDitadoDeFluxo(Destino().destino)).isFalse()
    }

    @Test
    fun `an unaccepted disclosure refuses to start and leaves no destination behind`() = runTest {
        val recorder = Recorder(null)
        val coordinator = OverlayCoordinator(
            scope = this, configProvider = { Config() }, recorder = recorder, transcriber = Transcriber(TranscriptionResult.Success("")),
            insertOrHold = { InsertOutcome.Inserted }, flushPending = { false }, targetAvailable = { true }, render = {},
            recordingConsentAccepted = { false },
        )
        assertThat(coordinator.iniciarDitadoDeFluxo(DestinoFluxo(null) {})).isFalse()
        assertThat(coordinator.ditadoDeFluxo).isFalse()
        assertThat(recorder.started).isEqualTo(0)
    }

    @Test
    fun `abandoning discards the recording at once without telling the destination`() = runTest {
        val amb = Ambiente(this, audio("k.m4a"), TranscriptionResult.Success("x"), { 0L })
        val destino = Destino()
        amb.coordinator.iniciarDitadoDeFluxo(destino.destino)
        amb.coordinator.abandonarDitadoDeFluxo()
        advanceUntilIdle()
        assertThat(destino.resultados).isEmpty()
        assertThat(amb.recorder.discarded).isEqualTo(1)
        assertThat(amb.coordinator.currentState()).isEqualTo(ButtonState.IDLE_READY)
        assertThat(amb.coordinator.ditadoDeFluxo).isFalse()
    }

    @Test
    fun `an ordinary session is untouched by the hook`() = runTest {
        var agora = 0L
        val amb = Ambiente(this, audio("l.m4a"), TranscriptionResult.Success("de sempre"), { agora })
        amb.coordinator.onTap()
        agora = 2_000
        amb.coordinator.onTap()
        advanceUntilIdle()
        assertThat(amb.inseridos).containsExactly("de sempre")
        assertThat(amb.coordinator.ditadoDeFluxo).isFalse()
    }
}
