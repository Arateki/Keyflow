package com.keyflow.overlay

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.core.ErrorKind
import com.keyflow.core.ErrorNavTarget
import com.keyflow.core.InsertOutcome
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import com.keyflow.data.EffectiveConfig
import com.keyflow.transcription.TranscriptionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** NM-T9: o coordinator com o [RoutingTranscriber] — Local vai ao engine e nunca à rede (PRV-01, NAT-04). */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val local = EffectiveConfig.Local(modelId = "whisper-base-int8", language = "pt")

    private class CountingHttp : OverlayTranscriber {
        var calls = 0
        var cancels = 0

        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            calls++
            return TranscriptionResult.Success("from the network")
        }

        override fun cancelInFlight() { cancels++ }
    }

    private class FakeLocal(private val result: TranscriptionResult) : LocalTranscription {
        val requests = mutableListOf<EffectiveConfig.Local>()

        override suspend fun transcribe(local: EffectiveConfig.Local, audio: File): TranscriptionResult {
            requests += local
            return result
        }
    }

    private class Recorder(private val file: File) : OverlayAudioRecorder {
        override fun start() = Unit
        override fun stop(): File = file
    }

    private fun coordinator(
        scope: CoroutineScope,
        cfg: Config,
        transcriber: OverlayTranscriber,
        inserted: MutableList<String>,
        errors: MutableList<Pair<String, ErrorNavTarget>>,
        states: MutableList<ButtonState> = mutableListOf(),
    ): OverlayCoordinator {
        var t = 0L
        return OverlayCoordinator(
            scope = scope,
            configProvider = { cfg },
            recorder = Recorder(tmp.newFile("audio.m4a")),
            transcriber = transcriber,
            insertOrHold = { inserted += it; InsertOutcome.Inserted },
            flushPending = { false },
            targetAvailable = { true },
            render = { states += it },
            onError = { message, target -> errors += message to target },
            recordingClockMs = { t.also { t += 500L } },
        )
    }

    /** O que o SettingsRepository entrega com a origem Local (sem endpoint nem token). */
    private val localConfig = Config(timeoutMs = Config.LOCAL_MIN_TIMEOUT_MS, local = local)

    @Test
    fun `local success is inserted and the network is never touched`() = runTest {
        val http = CountingHttp()
        val engine = FakeLocal(TranscriptionResult.Success("olá do aparelho"))
        val inserted = mutableListOf<String>()
        val c = coordinator(this, localConfig, RoutingTranscriber(http, engine), inserted, mutableListOf())

        c.onTap()
        c.onTap()
        advanceUntilIdle()

        assertThat(inserted).containsExactly("olá do aparelho")
        assertThat(engine.requests).containsExactly(local)
        assertThat(http.calls).isEqualTo(0)
    }

    @Test
    fun `local failure shows its own message, points to the voice tab and has no network fallback`() = runTest {
        val http = CountingHttp()
        val engine = FakeLocal(TranscriptionResult.Error(ErrorKind.LOCAL, "Memória insuficiente para o modelo local."))
        val inserted = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, ErrorNavTarget>>()
        val states = mutableListOf<ButtonState>()
        val c = coordinator(this, localConfig, RoutingTranscriber(http, engine), inserted, errors, states)

        c.onTap()
        c.onTap()
        advanceUntilIdle()

        assertThat(inserted).isEmpty()
        assertThat(errors).containsExactly("Memória insuficiente para o modelo local." to ErrorNavTarget.VOZ)
        assertThat(states).contains(ButtonState.ERROR)
        assertThat(http.calls).isEqualTo(0)
    }

    @Test
    fun `local without a model is config missing and does not reach the network`() = runTest {
        val engine = FakeLocal(TranscriptionResult.Success("nunca"))
        val errors = mutableListOf<Pair<String, ErrorNavTarget>>()
        // Sem modelo o SettingsRepository entrega Config() — e o cliente HTTP real para antes da rede.
        val c = coordinator(this, Config(), RoutingTranscriber(AndroidHttp(), engine), mutableListOf(), errors)

        c.onTap()
        c.onTap()
        advanceUntilIdle()

        assertThat(errors.single().second).isEqualTo(ErrorNavTarget.VOZ)
        assertThat(errors.single().first).contains("Configure")
        assertThat(engine.requests).isEmpty()
    }

    @Test
    fun `remote sources keep going through http`() = runTest {
        val http = CountingHttp()
        val engine = FakeLocal(TranscriptionResult.Success("nunca"))
        val inserted = mutableListOf<String>()
        val remote = Config(endpointUrl = "https://example.test", authHeaderValue = "Bearer x")
        val c = coordinator(this, remote, RoutingTranscriber(http, engine), inserted, mutableListOf())

        c.onTap()
        c.onTap()
        advanceUntilIdle()

        assertThat(inserted).containsExactly("from the network")
        assertThat(engine.requests).isEmpty()
    }

    @Test
    fun `cancel only aborts http`() {
        val http = CountingHttp()

        RoutingTranscriber(http, FakeLocal(TranscriptionResult.Success(""))).cancelInFlight()

        assertThat(http.cancels).isEqualTo(1)
    }

    /** O cliente HTTP de verdade: com config incompleta devolve CONFIG_MISSING sem abrir conexão. */
    private class AndroidHttp : OverlayTranscriber {
        private val client = TranscriptionClient()
        override suspend fun transcribe(audio: File, cfg: Config) = client.transcribe(audio, cfg)
    }
}
