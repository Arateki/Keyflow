package com.keyflow.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.ime.keyboard.KeyboardGridView
import com.keyflow.ime.keyboard.layout.KeyAction as CanvasKeyAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado do provider de acessibilidade da grade Canvas (KC-T8/A11Y-01..02): cada tecla vira um nó
 * virtual do `ExploreByTouchHelper` (bounds + label) e `ACTION_CLICK` aciona a mesma ação base de um
 * toque normal. A leitura em voz alta do TalkBack e o duplo-toque real são validados **manualmente**.
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardAccessibilityTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    @After
    fun tearDown() {
        shell("ime reset")
        device.pressHome()
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun eachKeyIsExposedAsAVirtualNodeWithLabelAndBounds() {
        val grid = launchProbeAndGetGrid()
        val index = indexOf(grid, "q")

        val provider = grid.accessibilityNodeProvider
        assertNotNull("ExploreByTouchHelper deveria instalar um AccessibilityNodeProvider", provider)
        val node = provider!!.createAccessibilityNodeInfo(index)
        assertNotNull("nó virtual da tecla \"q\" deveria existir", node)
        assertEquals("q", node!!.contentDescription?.toString())
        val bounds = android.graphics.Rect()
        node.getBoundsInParent(bounds)
        assertTrue("bounds da tecla deveriam ter área > 0", bounds.width() > 0 && bounds.height() > 0)
    }

    @Test
    fun actionClickOnAVirtualNodeActivatesTheSameBaseActionAsATap() {
        val grid = launchProbeAndGetGrid()
        val index = indexOf(grid, "q")
        val provider = grid.accessibilityNodeProvider!!

        val performed = provider.performAction(index, AccessibilityNodeInfo.ACTION_CLICK, null)

        assertTrue("performAction(ACTION_CLICK) deveria reportar sucesso", performed)
        waitUntil("clique de acessibilidade deveria comitar \"q\" no campo") {
            device.findObject(By.desc("ime_probe_field"))?.text?.toString() == "q"
        }
    }

    // ── ações de alto nível ──────────────────────────────────────────────────

    private fun launchProbeAndGetGrid(): KeyboardGridView {
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
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!
        waitUntil("grade Canvas ainda sem geometria medida") { grid.placedRowsForTest.isNotEmpty() }
        return grid
    }

    private fun indexOf(grid: KeyboardGridView, label: String): Int {
        val all = grid.placedRowsForTest.flatMap { it.keys }
        return all.indexOfFirst { (it.keyDef.action as? CanvasKeyAction.Char)?.text == label }
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
