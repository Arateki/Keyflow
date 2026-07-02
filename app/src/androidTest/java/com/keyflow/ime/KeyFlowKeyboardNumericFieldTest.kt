package com.keyflow.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.text.InputType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.ime.keyboard.layout.LayerId
import com.keyflow.ui.ImeProbeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado do NUM-01 (grade Canvas real): um campo `TYPE_CLASS_NUMBER`/`TYPE_CLASS_PHONE` trava a
 * grade na camada numérica; voltar pra um campo de texto normal restaura as letras — tudo na MESMA
 * instância de `KeyFlowKeyboardView`, já que a view é reusada entre campos (só `onStartInputView` roda de
 * novo, `onCreateInputView` não), diferente da troca de idioma (KC-T9), que força recriação.
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardNumericFieldTest {

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
    fun numberClassFieldLocksTheGridOnTheNumberLayer() {
        launchProbe(inputType = InputType.TYPE_CLASS_NUMBER)

        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest
        assertNotNull("grade Canvas deveria estar ativa", grid)
        assertEquals(LayerId.NUMBER, grid!!.currentLayerIdForTest)
    }

    @Test
    fun phoneClassFieldAlsoLocksOnTheNumberLayer() {
        launchProbe(inputType = InputType.TYPE_CLASS_PHONE)

        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest
        assertEquals(LayerId.NUMBER, grid!!.currentLayerIdForTest)
    }

    @Test
    fun returningToATextFieldRestoresLettersOnTheSameReusedView() {
        launchProbe(inputType = InputType.TYPE_CLASS_NUMBER)
        val view = KeyFlowKeyboardService.activeViewForTest!!
        assertEquals(LayerId.NUMBER, view.canvasGridForTest!!.currentLayerIdForTest)

        launchProbe(inputType = InputType.TYPE_CLASS_TEXT)

        // Mesma instância de view (nenhuma troca de idioma pra forçar recriação) — só reconfigurada.
        assertSame(view, KeyFlowKeyboardService.activeViewForTest)
        assertEquals(LayerId.LETTERS, view.canvasGridForTest!!.currentLayerIdForTest)
    }

    // ── ações de alto nível ──────────────────────────────────────────────────

    private fun launchProbe(inputType: Int) {
        uiPrefs.edit().putBoolean(KEY_CANVAS_GRID, true).apply()
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("ime enable $IME")
        shell("ime set $IME")

        val intent = Intent()
            .setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(ImeProbeActivity.EXTRA_INPUT_TYPE, inputType)
        context.startActivity(intent)

        device.wait(Until.hasObject(By.desc("ime_probe_field")), TIMEOUT)
        device.findObject(By.desc("ime_probe_field"))?.click()
        device.wait(Until.hasObject(By.text("🎙")), TIMEOUT)
        waitUntil("KeyFlowKeyboardView não anexou") { KeyFlowKeyboardService.activeViewForTest != null }
        waitUntil("grade Canvas ainda sem geometria medida") {
            KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest?.placedRowsForTest?.isNotEmpty() == true
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
        const val IME = "com.keyflow/.ime.KeyFlowKeyboardService"
        const val TIMEOUT = 5000L
        const val KEY_CANVAS_GRID = "kbd_canvas"
    }
}
