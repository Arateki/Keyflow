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
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.ime.bars.BarView
import android.graphics.Rect
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

    // Preserva a config real de quem é dono do aparelho — ver [UserSettingsGuard].
    private val userSettings = UserSettingsGuard(context)

    @Before
    fun setUp() {
        userSettings.capture()
        // Este teste cobre o SELETOR DA BARRA ANTIGA, que deixou de ser o padrão em 2026-09-07 (ROL-02).
        // Enquanto esse caminho existir no código, o teste força a flag para exercitá-lo; quando a barra
        // antiga sair, este arquivo migra para o seletor equivalente da BarView (MicSelectorController).
        context.getSharedPreferences("keyflow_kb_ui", Context.MODE_PRIVATE)
            .edit().putBoolean("kbd_bars", false).commit()
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("ime enable $IME")
        shell("ime set $IME")

        val intent = Intent()
            .setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        device.wait(Until.hasObject(By.desc("ime_probe_field")), TIMEOUT)
        device.findObject(By.desc("ime_probe_field"))?.click()
        // Nunca acha o "🎙" (o mic é desenhado pela BarView): na prática são 5 s para a entrada do teclado assentar. Tirá-los
        // rendeu 4 falhas de teclado em 2 rodadas do CI (2026-10-10). Trocar só por uma condição que espere o mesmo assentamento.
        device.wait(Until.hasObject(By.text("🎙")), TIMEOUT)
        awaitKeyboardAttached(device) { context.startActivity(intent) }
    }

    @After
    fun tearDown() {
        runCatching { context.stopService(com.keyflow.overlay.OverlayService.intent(context)) }
        context.getSharedPreferences("keyflow_kb_ui", Context.MODE_PRIVATE).edit().remove("kbd_bars").commit()
        userSettings.restore()
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
        // O mic é desenhado pela BarView (ROL-02) — quem o expõe é o nó de acessibilidade.
        (device.findObject(By.desc("🎙")) ?: device.findObject(By.text("🎙")))?.click()
        device.wait(Until.hasObject(By.textContains("●")), TIMEOUT)
        waitUntilButtonState(ButtonState.RECORDING)
    }

    private var micDownX = 0
    private var micDownY = 0
    private var gestureDownTime = 0L

    private fun holdMic() {
        val mic = KeyFlowKeyboardService.activeViewForTest!!.micScreenRect()
        micDownX = mic.centerX()
        micDownY = mic.centerY()
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
     * Hold → arrasta até [action] → solta, esperando o estado resultante. O gesto às vezes não surte
     * efeito mesmo com a geometria correta (`highlightedIndexForTest` confirmado certo antes do UP), daí
     * as tentativas.
     *
     * A explicação antiga — "a entrega do toque via `sendPointerSync` é intermitente sob carga" — foi
     * medida em `KeyFlowKeyboardSpellCheckerTest` (2026-09-08) e lá era FALSA: o toque chegava e o
     * `InputConnection` aceitava o texto; a leitura pelo UiAutomator é que estava velha. Aqui a causa
     * NÃO foi medida — se este teste voltar a incomodar, medir antes de repetir aquela explicação.
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

// ── ponte para a BarView ────────────────────────────────────────────────────
// O mic e o seletor deixaram de ser Views próprias quando as barras Canvas viraram o padrão (ROL-02):
// hoje o mic é desenhado pela BarView e o seletor é o MicSelectorController. Os acessores abaixo mantêm
// a leitura que estes testes já faziam, agora apontando para a barra ativa.

/**
 * A barra viva. `barViewsForTest` acumula as barras dos DOIS modos (o `init` constrói o expandido e o
 * colapsado), e `setExpanded` ainda reconstrói a pilha — então quem vale é a que está de fato na tela;
 * a última da lista serve só de rede quando nada foi anexado ainda (view fora de janela, em teste).
 */
private val KeyFlowKeyboardView.activeBar: BarView
    get() = barViewsForTest.lastOrNull { it.isShown } ?: barViewsForTest.last()
private val KeyFlowKeyboardView.selectorOpenForTest: Boolean get() = activeBar.selectorOpenForTest
private val KeyFlowKeyboardView.popupActionsForTest: List<MicAction> get() = activeBar.popupActionsForTest
private val KeyFlowKeyboardView.popupRectsForTest: List<Rect> get() = activeBar.popupRectsForTest
private val KeyFlowKeyboardView.highlightedIndexForTest: Int get() = activeBar.highlightedIndexForTest

/** Retângulo de tela do botão de mic dentro da barra ativa. */
private fun KeyFlowKeyboardView.micScreenRect(): Rect {
    val bar = activeBar
    val index = bar.layoutForTest.buttons.indexOfFirst { it.button == BarButton.Mic }
    check(index >= 0) { "a barra ativa não tem botão de mic: ${bar.layoutForTest.buttons.map { it.button }}" }
    return bar.buttonScreenRectForTest(index)
}
