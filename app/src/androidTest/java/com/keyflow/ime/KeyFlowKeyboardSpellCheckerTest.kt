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
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.data.SettingsRepository
import com.keyflow.ime.keyboard.KeyboardGridView
import com.keyflow.ime.keyboard.layout.KeyAction as CanvasKeyAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado da composição + corretor do sistema no IME **real** (KC-T7/SPL-01..03): digitar letras
 * na grade Canvas compõe (`setComposingText`) em vez de comitar direto; separador/backspace-até-vazio
 * encerram a composição corretamente; a quantidade/conteúdo exato das sugestões do corretor depende do
 * pacote instalado no device e é validada **manualmente** (esta suíte só garante a mecânica, sem crash).
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardSpellCheckerTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    // Preserva o que o dono do aparelho já tinha configurado — ver [UserSettingsGuard].
    private val userSettings = UserSettingsGuard(context)

    @Before
    fun captureUserSettings() {
        userSettings.capture()
        // A grade só entra na hierarquia (e só é medida) com o teclado expandido.
        userSettings.forceExpanded()
    }

    @After
    fun tearDown() {
        userSettings.restore()
        device.pressHome()
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun typingLettersComposesTheWordInsteadOfCommittingEachLetter() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!

        performUntil("o") { tapKey(grid, "o") }
        performUntil("oi") { tapKey(grid, "i") }
    }

    @Test
    fun spaceFinishesComposingAndCommitsANormalSpace() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!

        performUntil("o") { tapKey(grid, "o") }
        performUntil("oi") { tapKey(grid, "i") }
        // Vírgula (4ª fileira da grade, KC-T10): também precisa encerrar a composição.
        performUntil("oi,") { tapKey(grid, ",") }
    }

    @Test
    fun backspaceDownToEmptyClearsTheComposedLetterWithoutDeletingBefore() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!

        performUntil("o") { tapKey(grid, "o") }
        performUntil("o,") { tapKey(grid, ",") } // texto prévio antes da nova palavra
        performUntil("o,a") { tapKey(grid, "a") }
        performUntil("o,") { tapBackspaceOnGrid(grid) }
    }

    @Test
    fun candidatesViewExistsAndDoesNotCrashWhenEmpty() {
        // A faixa só existe se ALGUMA barra declarar o slot `Candidates` (BarView), e isso é config de
        // quem usa: no A32 do Yan nenhuma barra o declarava e este teste caía sem haver defeito nenhum
        // (2026-09-08). Monta a config que o teste realmente exige; o `UserSettingsGuard` devolve a dele.
        val withCandidates = BarsConfig(
            mirrored = true,
            collapsed = listOf(BarDef(listOf(BarButton.Mic, BarButton.Candidates, BarButton.ToggleExpand))),
            expanded = emptyList(),
        )
        runBlocking { SettingsRepository(context).saveBarsConfig(withCandidates) }

        launchProbe()
        val view = KeyFlowKeyboardService.activeViewForTest!!
        assertNotNull("com o slot Candidates na barra a faixa deveria existir", view.candidatesViewForTest)
        assertTrue(view.candidatesViewForTest!!.candidatesForTest.isEmpty())
    }

    // ── ações de alto nível ──────────────────────────────────────────────────

    private fun launchProbe() {
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
        waitUntil("grade Canvas não montou") { KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest != null }
    }

    private fun tapKey(grid: KeyboardGridView, label: String) {
        waitUntil("grade Canvas ainda sem geometria medida") { grid.placedRowsForTest.isNotEmpty() }
        val placed = grid.placedRowsForTest.flatMap { it.keys }
            .first { (it.keyDef.action as? CanvasKeyAction.Char)?.text == label }
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        val x = (loc[0] + (placed.left + placed.right) / 2).toInt()
        val y = (loc[1] + (placed.top + placed.bottom) / 2).toInt()
        tapScreen(x, y)
    }

    private fun tapBackspaceOnGrid(grid: KeyboardGridView) {
        val placed = grid.placedRowsForTest.flatMap { it.keys }
            .first { it.keyDef.action == CanvasKeyAction.Backspace }
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        val x = (loc[0] + (placed.left + placed.right) / 2).toInt()
        val y = (loc[1] + (placed.top + placed.bottom) / 2).toInt()
        tapScreen(x, y)
    }

    private fun tapScreen(x: Int, y: Int) {
        val time = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, x, y, time)
        inject(MotionEvent.ACTION_UP, x, y, SystemClock.uptimeMillis())
    }

    private fun inject(action: Int, x: Int, y: Int, time: Long) {
        val event = MotionEvent.obtain(time, time, action, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        instrumentation.sendPointerSync(event)
        event.recycle()
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

    private fun fieldText(): String? = device.findObject(By.desc("ime_probe_field"))?.text?.toString()

    /**
     * Repete [action] até o campo mostrar [expectedText] (a entrega do toque real via
     * `sendPointerSync` é intermitente sob a carga deste host — mesma classe de flakiness já observada
     * no seletor de ações do mic) em vez de aceitar um falso negativo de uma tecla que não registrou.
     */
    private fun performUntil(expectedText: String, attempts: Int = 3, action: () -> Unit) {
        repeat(attempts) {
            action()
            if (waitUntilOrFalse(1500) { fieldText() == expectedText }) return
        }
        throw AssertionError("campo não chegou a \"$expectedText\" após $attempts tentativas; atual: ${fieldText()}")
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
