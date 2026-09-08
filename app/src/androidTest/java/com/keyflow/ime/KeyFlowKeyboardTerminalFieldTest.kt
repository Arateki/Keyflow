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
import com.keyflow.ui.ImeProbeActivity
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado do bug do Termius (Q3, 2026-07-02): campos `TYPE_NULL` (terminal/raw-key-only, como
 * Termux/Termius — confirmado por Yan: texto selecionável/com pinça, típico de terminal em WebView) não
 * renderizam texto "compondo" (`setComposingText`), só texto já comitado. A decisão real (compor vs.
 * comitar direto) é pura e coberta em unit (`KeyFlowKeyboardServiceTest.shouldCompose`); este teste cobre
 * só a fiação real do `EditorInfo` real → `isRawInputField`, no IME de verdade.
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardTerminalFieldTest {

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
    fun rawTerminalStyleFieldIsFlaggedAsRawInput() {
        launchProbe(InputType.TYPE_NULL)
        assertTrue(KeyFlowKeyboardService.isRawInputFieldForTest)
    }

    @Test
    fun normalTextFieldIsNotFlaggedAsRawInput() {
        launchProbe(InputType.TYPE_CLASS_TEXT)
        assertFalse(KeyFlowKeyboardService.isRawInputFieldForTest)
    }

    // ── ações de alto nível ──────────────────────────────────────────────────

    private fun launchProbe(inputType: Int) {
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
        waitUntil("KeyFlowKeyboardView não anexou") { KeyFlowKeyboardService.activeViewForTest != null }
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
    }
}
