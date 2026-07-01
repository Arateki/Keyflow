package com.keyflow.overlay

import android.Manifest
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado de cancelar/desfazer (CANCEL-01..06). Dirige o [OverlayService] real pelo caminho
 * estável de ações/Intents (`ACTION_RECORD`/`ACTION_UNDO_CANCEL`/`ACTION_COMMIT_CANCEL`), não pelo
 * gesto físico (frágil) — espelhando a decisão G1. Observa o estado via [KeyFlowController.buttonState],
 * o mesmo `StateFlow` público que o `OverlayCoordinator.render()` atualiza a cada transição.
 */
@RunWith(AndroidJUnit4::class)
class OverlayServiceCancelTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        // Sobe o serviço já em RECORDING: ACTION_RECORD chama ensureForeground() (startForeground
        // imediato, sem warm-up extra) e coordinator.onTap() (ocioso → grava, MIN_RECORDING_MS à parte).
        context.startForegroundService(OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
        waitUntilState(ButtonState.RECORDING)
    }

    @After
    fun tearDown() {
        context.stopService(OverlayService.intent(context))
    }

    @Test
    fun cancelEntersCancellingAndShowsUndoBanner() {
        // CANCEL-01: grava/pausado + cancelar → CANCELLING (áudio retido, não descartado já).
        sendAction(RecordingNotification.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)

        // CANCEL-02: banner "cancelado · desfazer" visível na tela (overlay clicável).
        assertTrue(
            "banner de desfazer não apareceu",
            device.wait(Until.hasObject(By.textContains("desfazer")), TIMEOUT) != null,
        )
    }

    @Test
    fun undoCancelReturnsToPaused() {
        sendAction(RecordingNotification.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)

        // CANCEL-03: desfazer dentro da janela → volta a PAUSED com o áudio preservado.
        sendAction(OverlayService.ACTION_UNDO_CANCEL)
        waitUntilState(ButtonState.PAUSED)
    }

    @Test
    fun cancelExpiresAndDiscardsAudio() {
        sendAction(RecordingNotification.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)

        // CANCEL-04: sem ação dentro da janela (cancelWindowMs = 3000ms) → descarta e volta ao idle.
        waitUntilState(ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY, timeoutMs = 6000)
    }

    @Test
    fun commitCancelEndsImmediatelyWithoutWaitingWindow() {
        sendAction(RecordingNotification.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)

        val start = System.currentTimeMillis()
        // CANCEL-05: toque na orbe durante CANCELLING conclui o descarte JÁ (sem esperar os 3s).
        sendAction(OverlayService.ACTION_COMMIT_CANCEL)
        waitUntilState(ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY, timeoutMs = 2000)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("commit deveria ser bem mais rápido que a janela de 3s (foi ${elapsed}ms)", elapsed < 2500)
    }

    @Test
    fun targetChangeDuringCancellingPreservesState() {
        sendAction(RecordingNotification.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)

        // CANCEL-06: foco de campo muda durante CANCELLING → o estado PERMANECE CANCELLING (não
        // orfaniza o job de descarte) — lição já registrada no projeto.
        instrumentation.runOnMainSync { KeyFlowController.registerInserter { true } }
        assertEquals(ButtonState.CANCELLING, KeyFlowController.buttonState.value)
        instrumentation.runOnMainSync { KeyFlowController.clearInserter() }
        assertEquals(ButtonState.CANCELLING, KeyFlowController.buttonState.value)

        // O job de desfazer sobrevive (não orfanizou): ainda dá para desfazer normalmente.
        sendAction(OverlayService.ACTION_UNDO_CANCEL)
        waitUntilState(ButtonState.PAUSED)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun sendAction(action: String) {
        context.startService(OverlayService.actionIntent(context, action))
    }

    private fun waitUntilState(vararg expected: ButtonState, timeoutMs: Long = TIMEOUT) {
        waitUntil("esperando $expected, estado atual: ${KeyFlowController.buttonState.value}", timeoutMs) {
            KeyFlowController.buttonState.value in expected
        }
    }

    private fun waitUntil(message: String, timeoutMs: Long = TIMEOUT, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun shell(command: String) {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        try {
            java.io.FileInputStream(fd.fileDescriptor).readBytes()
        } finally {
            fd.close()
        }
    }

    private companion object {
        const val TIMEOUT = 5000L
    }
}
