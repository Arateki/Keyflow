package com.keyflow.overlay

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.ButtonState
import com.keyflow.core.InsertOutcome
import com.keyflow.core.RecordingMode
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import com.keyflow.ui.orb.OrbDragKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * Integração ponta a ponta: gesto REAL na [OrbButtonView] (arrasto/toque, o mesmo caminho que o dedo do
 * usuário percorre) fiado a um [OverlayCoordinator] REAL (não um fake de `onIntent`) — a mesma fiação que
 * o [OverlayService] monta em produção. `OrbButtonViewTest` cobre cada gesto isolado com um `onIntent`
 * fake; `OverlayCoordinatorTest` cobre a máquina de estados isolada chamando a API direto
 * (`onCancel()`/`onIntent(RESUME)`/...). Nenhum dos dois prova que o gesto físico realmente atravessa as
 * duas camadas até a gravação/transcrição/inserção — é essa costura que este arquivo verifica (pedido do
 * Yan, 2026-07-04).
 */
@RunWith(AndroidJUnit4::class)
class OrbGestureIntegrationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private var scope: CoroutineScope? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        scope?.cancel()
    }

    /** Fiação real (View + Coordinator), espelhando [OverlayService.createCoordinator]/`addFloatingButton`. */
    private class Harness(
        val view: OrbButtonView,
        val coordinator: OverlayCoordinator,
        val recorder: FakeRecorder,
        val transcriber: OverlayTranscriber,
        val inserted: MutableList<String>,
        val renderedStates: MutableList<ButtonState>,
    )

    private fun harness(
        mode: RecordingMode = RecordingMode.TOGGLE,
        recorder: FakeRecorder = FakeRecorder(),
        transcriber: OverlayTranscriber = FakeTranscriber(TranscriptionResult.Success("transcrito")),
        cancelWindowMs: Long = 3000L,
        concatAudioSegments: (List<File>, File) -> File = { segments, dir ->
            File(dir, "merged.m4a").also { it.writeText(segments.joinToString("+") { f -> f.readText() }) }
        },
    ): Harness {
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        val inserted = mutableListOf<String>()
        val renderedStates = mutableListOf<ButtonState>()

        // Em produção, o `pttReleaseWindowMs` do controller e o `tapBurstWindowMs` da view leem a MESMA
        // configuração ao vivo (ver OverlayService.createGestureController/addFloatingButton) — aqui os
        // dois apontam pro mesmo valor fixo, senão o release do PTT nunca bateria o limiar a tempo.
        val tapBurstWindowMs = 250L
        lateinit var coordinator: OverlayCoordinator
        val controller = RecordingGestureController(
            mode = { mode },
            currentState = { coordinator.currentState() },
            pttReleaseWindowMs = { tapBurstWindowMs },
        )
        val view = OrbButtonView(
            context = context,
            controller = controller,
            mode = { mode },
            onIntent = { coordinator.onIntent(it) },
            tapBurstWindowMs = tapBurstWindowMs,
        )
        coordinator = OverlayCoordinator(
            scope = newScope,
            configProvider = {
                Config(endpointUrl = "https://example.test/v1/audio/transcriptions", authHeaderValue = "Bearer token")
            },
            recorder = recorder,
            transcriber = transcriber,
            insertOrHold = { inserted += it; InsertOutcome.Inserted },
            flushPending = { false },
            targetAvailable = { true },
            render = { state -> renderedStates += state; view.render(state) },
            cancelWindowMs = cancelWindowMs,
            concatAudioSegments = concatAudioSegments,
        )
        view.render(coordinator.currentState())
        return Harness(view, coordinator, recorder, transcriber, inserted, renderedStates)
    }

    private fun waitUntil(message: String, timeoutMs: Long = 3000L, condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun tap(view: OrbButtonView, time: Long) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 50f, y = 50f, time = time))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x = 50f, y = 50f, time = time + 20))
    }

    private fun startHold(view: OrbButtonView, x: Float = 100f, y: Float = 100f) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = x, y = y, time = 0))
        waitUntil("modo-arrasto não travou (dragLockedForTest)") { view.dragLockedForTest }
    }

    private fun dragTo(view: OrbButtonView, dx: Float, dy: Float, x0: Float = 100f, y0: Float = 100f) {
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = x0 + dx, y = y0 + dy, time = 30))
    }

    private fun release(view: OrbButtonView, x: Float, y: Float) {
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x = x, y = y, time = 60))
    }

    /** Arrasto lateral (destro=RIGHT por padrão): dx negativo é "pro centro" = cancelar. */
    private fun dragCancel(view: OrbButtonView) {
        startHold(view)
        dragTo(view, dx = -DRAG_DISTANCE, dy = 0f)
        waitUntil("dragKindForTest não resolveu CANCEL") { view.dragKindForTest == OrbDragKind.CANCEL }
        release(view, x = 100f - DRAG_DISTANCE, y = 100f)
    }

    /** Arrasto pra cima: pausa se estava gravando, retoma se estava pausado. */
    private fun dragUp(view: OrbButtonView, expectedKind: OrbDragKind) {
        startHold(view)
        dragTo(view, dx = 0f, dy = -DRAG_DISTANCE)
        waitUntil("dragKindForTest não resolveu $expectedKind") { view.dragKindForTest == expectedKind }
        release(view, x = 100f, y = 100f - DRAG_DISTANCE)
    }

    private fun event(action: Int, x: Float, y: Float, time: Long): MotionEvent =
        MotionEvent.obtain(0, time, action, x, y, 0)

    // ── cadeia completa pedida pelo Yan: gravar → cancelar (arrasto) → desfazer → retomar → enviar ──

    @Test
    fun recordCancelUndoResumeSend_insertsTheTranscription() {
        val h = harness()

        tap(h.view, 0) // toque real → START
        waitUntil("não iniciou RECORDING") { h.view.renderedState == ButtonState.RECORDING }
        Thread.sleep(450) // garante duração mínima (MIN_RECORDING_MS) antes do envio final

        dragCancel(h.view) // arrasto real → CANCEL
        waitUntil("não entrou em CANCELLING") { h.view.renderedState == ButtonState.CANCELLING }
        assertEquals(1, h.recorder.paused)

        h.coordinator.onUndoCancel() // "Desfazer" do banner (não é gesto da orbe — botão à parte)
        waitUntil("não voltou a PAUSED") { h.view.renderedState == ButtonState.PAUSED }
        assertEquals(0, h.recorder.discarded)

        dragUp(h.view, OrbDragKind.RESUME) // arrasto real → RESUME
        waitUntil("não retomou RECORDING") { h.view.renderedState == ButtonState.RECORDING }
        assertEquals(1, h.recorder.resumed)
        assertEquals(1, h.recorder.started) // só a sessão inicial: retomar chama resume(), não start() de novo

        tap(h.view, 200) // toque real → STOP/enviar
        waitUntil("não chegou em IDLE_READY após enviar") { h.view.renderedState == ButtonState.IDLE_READY }

        assertEquals(listOf("transcrito"), h.inserted)
        assertEquals(0, h.recorder.discarded)
    }

    @Test
    fun recordCancelCommitViaTap_discardsImmediatelyWithoutSending() {
        val h = harness()

        tap(h.view, 0)
        waitUntil("não iniciou RECORDING") { h.view.renderedState == ButtonState.RECORDING }

        dragCancel(h.view)
        waitUntil("não entrou em CANCELLING") { h.view.renderedState == ButtonState.CANCELLING }

        // Toque na orbe DURANTE o cancelamento conclui na hora (sem esperar a janela de desfazer).
        tap(h.view, 100)
        waitUntil("não descartou e voltou a IDLE_READY") { h.view.renderedState == ButtonState.IDLE_READY }

        assertEquals(1, h.recorder.discarded)
        assertTrue("não deveria ter transcrito nada", h.inserted.isEmpty())
    }

    @Test
    fun recordCancelWithoutUndo_expiresAndDiscardsAfterWindow() {
        val h = harness(cancelWindowMs = 300L)

        tap(h.view, 0)
        waitUntil("não iniciou RECORDING") { h.view.renderedState == ButtonState.RECORDING }

        dragCancel(h.view)
        waitUntil("não entrou em CANCELLING") { h.view.renderedState == ButtonState.CANCELLING }

        // Ninguém desfaz nem toca: a janela expira sozinha e descarta.
        waitUntil("a janela não expirou/descartou", timeoutMs = 2000) {
            h.view.renderedState == ButtonState.IDLE_READY
        }
        assertEquals(1, h.recorder.discarded)
        assertTrue(h.inserted.isEmpty())
    }

    @Test
    fun cancelDuringSendingThenUndoThenResumeContinuesAndMergesOnNextSend() {
        // 1ª transcrição trava (simula rede em andamento) até o cancelamento/desfazer acontecerem —
        // senão o envio do segmento 1 já teria voltado a IDLE antes de dar tempo de cancelar.
        val transcriber = HangingThenRespondingTranscriber(TranscriptionResult.Success("fundido"))
        val h = harness(transcriber = transcriber)

        tap(h.view, 0)
        waitUntil("não iniciou RECORDING") { h.view.renderedState == ButtonState.RECORDING }
        Thread.sleep(450)

        tap(h.view, 500) // envia o 1º segmento (fica pendurado no transcriber)
        waitUntil("não entrou em SENDING") { h.view.renderedState == ButtonState.SENDING }

        dragCancel(h.view) // cancela DURANTE o envio (só a lateral vale nesse estado)
        waitUntil("não entrou em CANCELLING") { h.view.renderedState == ButtonState.CANCELLING }

        h.coordinator.onUndoCancel()
        waitUntil("não voltou a PAUSED retendo o segmento 1") { h.view.renderedState == ButtonState.PAUSED }

        dragUp(h.view, OrbDragKind.RESUME) // retoma: sessão NOVA (o recorder original já foi liberado)
        waitUntil("não retomou gravando o 2º segmento") { h.view.renderedState == ButtonState.RECORDING }
        assertEquals(2, h.recorder.started) // sessão 1 (send) + sessão 2 (retomada pós-undo)
        Thread.sleep(450)

        tap(h.view, 1000) // funde os dois segmentos e envia
        waitUntil("não voltou a IDLE_READY após o envio fundido") { h.view.renderedState == ButtonState.IDLE_READY }

        assertEquals(listOf("fundido"), h.inserted)
    }

    // ── Push-to-talk: segurar grava, soltar abre a janela de release, estourar sem re-segurar envia ──

    @Test
    fun pushToTalkHoldThenReleaseWithoutRegrab_sendsAfterReleaseWindow() {
        val h = harness(mode = RecordingMode.PUSH_TO_TALK)

        h.view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 50f, y = 50f, time = 0))
        waitUntil("PTT não iniciou RECORDING") { h.view.renderedState == ButtonState.RECORDING }
        Thread.sleep(450)

        h.view.onTouchEvent(event(MotionEvent.ACTION_UP, x = 50f, y = 50f, time = 500))
        // Solta e NÃO re-segura: a janela de release estoura sozinha (agendada via scheduleReleaseTimeout).
        waitUntil("PTT não enviou após a janela de release", timeoutMs = 2000) {
            h.view.renderedState == ButtonState.IDLE_READY
        }

        assertEquals(listOf("transcrito"), h.inserted)
    }

    // ── Fakes locais (equivalentes aos de OverlayCoordinatorTest, redeclarados: source sets diferentes) ──

    private class FakeRecorder(private val audioFiles: List<File?> = emptyList()) : OverlayAudioRecorder {
        var started = 0
        var stopped = 0
        var paused = 0
        var resumed = 0
        var discarded = 0
        private val tmp = File.createTempFile("orb-gesture-test", ".m4a").also { it.deleteOnExit() }

        override fun start() { started++ }
        override fun stop(): File? {
            val file = audioFiles.getOrNull(stopped) ?: tmp.also { it.writeText("segment-$stopped") }
            stopped++
            return file
        }
        override fun pause() { paused++ }
        override fun resume() { resumed++ }
        override fun discard() { discarded++ }
    }

    private class FakeTranscriber(private val result: TranscriptionResult) : OverlayTranscriber {
        val audioFiles = mutableListOf<File>()
        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            audioFiles += audio
            return result
        }
    }

    /** A 1ª chamada trava até o cancelamento/desfazer acontecerem; as seguintes respondem na hora. */
    private class HangingThenRespondingTranscriber(private val result: TranscriptionResult) : OverlayTranscriber {
        private var callCount = 0
        var cancelCalls = 0
        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            callCount++
            if (callCount == 1) delay(60_000)
            return result
        }
        override fun cancelInFlight() { cancelCalls++ }
    }

    private companion object {
        const val DRAG_DISTANCE = 160f
    }
}
