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
import com.keyflow.ime.keyboard.layout.KeyAction as CanvasKeyAction
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado da integração híbrida da grade Canvas no IME **real** (KC-T6): com a flag `kbd_canvas`
 * ligada, `KeyFlowKeyboardView.buildExpanded()` troca as `buildRow` antigas por [com.keyflow.ime.keyboard.KeyboardGridView],
 * mas mic/action row continuam as mesmas Views (SYNC-01..04). Cobre: (a) a flag alterna antigo↔novo no
 * debug (ROL-02), (b) tocar a grade nova comita texto pelo mesmo `handleKey`, (c) o mic segue intacto.
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardCanvasGridTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val uiPrefs get() = context.getSharedPreferences("keyflow_kb_ui", Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        uiPrefs.edit().remove(KEY_CANVAS_GRID).apply()
        shell("ime reset")
        device.pressHome()
        // KeyFlowController é singleton de processo (sobrevive ao fim deste teste, dentro do mesmo gate).
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun flagOffKeepsTheOldGridByDefault() {
        launchProbe(canvasGrid = false)

        assertNull(KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest)
    }

    @Test
    fun flagOnSwapsInTheCanvasGridAndTypingCommitsTextThroughTheSameHandleKey() {
        launchProbe(canvasGrid = true)
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest
        assertNotNull("com a flag ligada a grade Canvas deveria substituir a antiga", grid)

        tapKey(grid!!, "q")

        waitUntil("o campo não recebeu \"q\" digitado pela grade nova") {
            device.findObject(By.desc("ime_probe_field"))?.text?.contains("q") == true
        }
    }

    @Test
    fun canvasGridDoesNotDisturbTheMicButton() {
        launchProbe(canvasGrid = true)

        // SYNC-01/02: mic continua a mesma View, tap ainda inicia gravação normalmente.
        device.findObject(By.text("🎙"))?.click()
        device.wait(Until.hasObject(By.textContains("●")), TIMEOUT)
        waitUntil("gravação não iniciou com a grade Canvas ativa") {
            KeyFlowController.buttonState.value == ButtonState.RECORDING
        }
    }

    // ── ações de alto nível ──────────────────────────────────────────────────

    private fun launchProbe(canvasGrid: Boolean) {
        uiPrefs.edit().putBoolean(KEY_CANVAS_GRID, canvasGrid).apply()
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

    /** Acha a tecla [label] na camada de letras via geometria já computada e injeta um toque real nela. */
    private fun tapKey(grid: com.keyflow.ime.keyboard.KeyboardGridView, label: String) {
        waitUntil("grade Canvas ainda sem geometria medida") { grid.placedRowsForTest.isNotEmpty() }
        val placed = grid.placedRowsForTest.flatMap { it.keys }
            .first { (it.keyDef.action as? CanvasKeyAction.Char)?.text == label }
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        val x = (loc[0] + (placed.left + placed.right) / 2).toInt()
        val y = (loc[1] + (placed.top + placed.bottom) / 2).toInt()
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
        const val IME = "com.keyflow/.ime.KeyFlowKeyboardService"
        const val TIMEOUT = 5000L
        const val KEY_CANVAS_GRID = "kbd_canvas"
    }
}
