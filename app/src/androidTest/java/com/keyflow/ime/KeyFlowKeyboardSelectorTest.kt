package com.keyflow.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
 * Instrumentado do seletor de ações por hold no mic do teclado (KBDCTL-01..04), no IME **real**: hold
 * no mic → seletor (chips conforme o estado) → arrastar+soltar no chip → ação entregue ao
 * [com.keyflow.overlay.OverlayService] → reflete no mesmo [KeyFlowController.buttonState] da orbe.
 *
 * A geometria dos chips é lida diretamente de [KeyFlowKeyboardService.activeViewForTest]
 * (`popupRectsForTest`/`popupActionsForTest`) em vez de consultar a hierarquia via UiAutomator no meio
 * do gesto: fazer um dump de acessibilidade com o dedo sintético ainda "pressionado" interfere no foco
 * do IME real (observado como `HIDE_RESET_SHELL_COMMAND` no logcat, fechando o teclado no meio do teste).
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardSelectorTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("ime enable $IME")
        shell("ime set $IME")

        val intent = Intent()
            .setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        device.wait(Until.hasObject(By.desc("ime_probe_field")), TIMEOUT)
        device.findObject(By.desc("ime_probe_field"))?.click()
        device.wait(Until.hasObject(By.text("🎙")), TIMEOUT)
        waitUntil("KeyFlowKeyboardView não anexou") { KeyFlowKeyboardService.activeViewForTest != null }
    }

    @After
    fun tearDown() {
        runCatching { context.stopService(com.keyflow.overlay.OverlayService.intent(context)) }
        shell("ime reset")
        device.pressHome()
        // KeyFlowController é um singleton de processo (sobrevive ao fim deste teste, dentro do mesmo
        // gate): sem isso, o próximo teste herdaria PAUSED/CANCELLING e o mic não mostraria "🎙" ocioso.
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun holdWhileRecordingOpensSelectorWithPauseAndCancel() {
        startRecordingByTappingMic()

        holdMic()
        // KBDCTL-01: RECORDING oferece pausar/retomar e cancelar.
        val view = KeyFlowKeyboardService.activeViewForTest!!
        assertEquals(listOf(MicAction.PAUSE_RESUME, MicAction.CANCEL), view.popupActionsForTest)
        releaseAtMic() // solta de volta no botão (nenhum chip destacado) → nenhuma ação

        waitUntilButtonState(ButtonState.RECORDING)
    }

    @Test
    fun dragToCancelChipEntersCancellingThenUndoChipReturnsToPaused() {
        startRecordingByTappingMic()

        // KBDCTL-02/03: arrasta até o chip e solta → aciona; entrega ao OverlayService (ACTION_CANCEL).
        selectChipAndWaitFor(MicAction.CANCEL, ButtonState.CANCELLING)

        // KBDCTL-01: em CANCELLING o seletor só oferece desfazer.
        holdMic()
        assertEquals(listOf(MicAction.UNDO), KeyFlowKeyboardService.activeViewForTest!!.popupActionsForTest)
        releaseAtMic()
        selectChipAndWaitFor(MicAction.UNDO, ButtonState.PAUSED)
    }

    @Test
    fun dragToPauseChipPausesThenResumeChipResumesRecording() {
        startRecordingByTappingMic()

        selectChipAndWaitFor(MicAction.PAUSE_RESUME, ButtonState.PAUSED)
        // KBDCTL-04: o mic do teclado reflete o mesmo estado/tempo do KeyFlowController (mesmo da orbe).
        selectChipAndWaitFor(MicAction.PAUSE_RESUME, ButtonState.RECORDING)
    }

    // ── ações de alto nível ──────────────────────────────────────────────────

    private fun startRecordingByTappingMic() {
        device.findObject(By.text("🎙"))?.click()
        device.wait(Until.hasObject(By.textContains("●")), TIMEOUT)
        waitUntilButtonState(ButtonState.RECORDING)
    }

    private var micDownX = 0
    private var micDownY = 0
    private var gestureDownTime = 0L

    private fun holdMic() {
        val mic = KeyFlowKeyboardService.activeViewForTest!!.micViewForTest!!
        val loc = IntArray(2).also { mic.getLocationOnScreen(it) }
        micDownX = loc[0] + mic.width / 2
        micDownY = loc[1] + mic.height / 2
        gestureDownTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, micDownX, micDownY, gestureDownTime)
        // HOLD_MS = 280ms na produção; folga para o host lento. Lê a geometria real do popup já aberto,
        // já com TODOS os chips medidos (rects não-vazios) — a 1ª passagem de layout de um LinearLayout
        // horizontal pode publicar o 1º chip antes do(s) seguinte(s) assentarem.
        waitUntil("seletor não abriu com a geometria completa (selectorOpenForTest/popupRectsForTest)", timeoutMs = 3000) {
            val view = KeyFlowKeyboardService.activeViewForTest
            val rects = view?.popupRectsForTest
            view?.selectorOpenForTest == true &&
                rects != null &&
                rects.size == view.popupActionsForTest.size &&
                rects.all { it.width() > 0 && it.height() > 0 }
        }
    }

    private fun dragAndReleaseOnAction(action: MicAction) {
        val view = KeyFlowKeyboardService.activeViewForTest!!
        val index = view.popupActionsForTest.indexOf(action)
        assertTrue("ação $action não está no seletor atual (${view.popupActionsForTest})", index >= 0)
        val rect = view.popupRectsForTest[index]
        val x = rect.centerX()
        val y = rect.centerY()
        inject(MotionEvent.ACTION_MOVE, x, y, SystemClock.uptimeMillis())
        Thread.sleep(80)
        inject(MotionEvent.ACTION_UP, x, y, SystemClock.uptimeMillis())
    }

    /**
     * Hold → arrasta até [action] → solta, esperando o estado resultante. A entrega do toque real
     * (`sendPointerSync`) é intermitente sob a carga deste host (mesma classe de flakiness já observada
     * no FGS/keystore) mesmo com a geometria correta (`highlightedIndexForTest` confirmado certo antes do
     * UP) — repete o gesto algumas vezes em vez de aceitar um falso negativo.
     */
    private fun selectChipAndWaitFor(action: MicAction, expected: ButtonState, attempts: Int = 3) {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                holdMic()
                dragAndReleaseOnAction(action)
                if (waitUntilOrFalse(timeoutMs = 2500) { KeyFlowController.buttonState.value == expected }) return
                lastError = AssertionError("estado ainda é ${KeyFlowController.buttonState.value}")
            } catch (e: Throwable) {
                lastError = e
            }
            // Recuperação antes de tentar de novo: garante que nenhum gesto ficou "pendurado".
            runCatching { releaseAtMic() }
            Thread.sleep(200)
        }
        throw AssertionError(
            "estado não chegou a $expected após $attempts tentativas de \"$action\"; " +
                "atual: ${KeyFlowController.buttonState.value}",
            lastError,
        )
    }

    private fun releaseAtMic() {
        inject(MotionEvent.ACTION_UP, micDownX, micDownY, SystemClock.uptimeMillis())
    }

    private fun inject(action: Int, x: Int, y: Int, time: Long) {
        val event = MotionEvent.obtain(gestureDownTime, time, action, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private fun waitUntilButtonState(expected: ButtonState, timeoutMs: Long = TIMEOUT) {
        waitUntil("esperando $expected, estado atual: ${KeyFlowController.buttonState.value}", timeoutMs) {
            KeyFlowController.buttonState.value == expected
        }
    }

    private fun waitUntil(message: String, timeoutMs: Long = TIMEOUT, condition: () -> Boolean) {
        if (!waitUntilOrFalse(timeoutMs, condition)) throw AssertionError(message)
    }

    private fun waitUntilOrFalse(timeoutMs: Long = TIMEOUT, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
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
        const val IME = "com.keyflow/.ime.KeyFlowKeyboardService"
        const val TIMEOUT = 5000L
    }
}
