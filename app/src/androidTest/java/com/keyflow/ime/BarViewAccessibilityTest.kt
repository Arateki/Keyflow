package com.keyflow.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import com.keyflow.core.shortcuts.PhysicalKeyMap
import com.keyflow.data.SettingsRepository
import com.keyflow.ime.bars.BarView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado do provider de acessibilidade da `BarView` (SB-T10/A11Y-01..02): cada botão vira um nó
 * virtual do `ExploreByTouchHelper` (bounds + label); a tecla avulsa aceita `ACTION_CLICK` e emite o
 * atalho normalmente (o caminho acessível dos atalhos); a alavanca e o gatilho de grade só anunciam a
 * limitação de gesto deslizante — a leitura em voz alta do TalkBack é validada manualmente (SB-T10).
 */
@RunWith(AndroidJUnit4::class)
class BarViewAccessibilityTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val uiPrefs get() = context.getSharedPreferences("keyflow_kb_ui", Context.MODE_PRIVATE)

    // Preserva o que o dono do aparelho já tinha configurado — ver [UserSettingsGuard].
    private val userSettings = UserSettingsGuard(context)

    @Before
    fun captureUserSettings() {
        userSettings.capture()
    }

    @After
    fun tearDown() {
        uiPrefs.edit().remove(KEY_BARS).apply()
        userSettings.restore()
        device.pressHome()
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun singleKeyButtonIsAClickableVirtualNodeAndActionClickEmitsTheShortcut() {
        val custom = BarsConfig(
            mirrored = true,
            collapsed = listOf(
                BarDef(
                    listOf(
                        BarButton.Mic,
                        BarButton.SingleKey(PhysicalKeyMap.key(KeyEvent.KEYCODE_B)),
                        BarButton.ToggleExpand,
                    )
                )
            ),
            expanded = emptyList(),
        )
        runBlocking { SettingsRepository(context).saveBarsConfig(custom) }
        val bar = launchProbeAndGetBar()
        val index = indexOf(bar) { it.button is BarButton.SingleKey }

        val provider = bar.accessibilityNodeProvider
        assertNotNull("BarView deveria instalar um AccessibilityNodeProvider", provider)
        val node = provider!!.createAccessibilityNodeInfo(index)
        assertNotNull("nó virtual da tecla avulsa deveria existir", node)
        assertTrue("tecla avulsa deveria ser clicável", node!!.isClickable)
        val bounds = android.graphics.Rect()
        node.getBoundsInParent(bounds)
        assertTrue("bounds do botão deveriam ter área > 0", bounds.width() > 0 && bounds.height() > 0)

        val performed = provider.performAction(index, AccessibilityNodeInfo.ACTION_CLICK, null)
        assertTrue("performAction(ACTION_CLICK) deveria reportar sucesso", performed)
        waitUntil("clique de acessibilidade deveria emitir o KeyEvent físico \"b\" no campo") {
            device.findObject(By.desc("ime_probe_field"))?.text?.contains("b") == true
        }
    }

    @Test
    fun joystickIsExposedButNotClickableAndAnnouncesTheGestureRequirement() {
        val config = BarsConfig(
            mirrored = true,
            collapsed = listOf(BarDef(listOf(BarButton.Mic, BarButton.Joystick, BarButton.ToggleExpand))),
            expanded = emptyList(),
        )
        runBlocking { SettingsRepository(context).saveBarsConfig(config) }
        val bar = launchProbeAndGetBar()
        val index = indexOf(bar) { it.button == BarButton.Joystick }

        val provider = bar.accessibilityNodeProvider!!
        val node = provider.createAccessibilityNodeInfo(index)!!
        assertFalse("alavanca não deveria ser clicável (gesto contínuo)", node.isClickable)
        assertTrue(
            "rótulo deveria anunciar a limitação de gesto deslizante",
            node.contentDescription?.contains("requer gesto deslizante") == true,
        )
    }

    // ── ações de alto nível (mesmo harness do KeyFlowKeyboardBarsTest) ────────

    private fun launchProbeAndGetBar(): BarView {
        uiPrefs.edit().putBoolean(KEY_BARS, true).apply()
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("ime enable $IME")
        shell("ime set $IME")

        val intent = Intent()
            .setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        device.wait(Until.hasObject(By.desc("ime_probe_field")), TIMEOUT)
        device.findObject(By.desc("ime_probe_field"))?.click()
        awaitKeyboardAttached(device) { context.startActivity(intent) }
        waitUntil("KeyFlowKeyboardView não ficou visível") {
            KeyFlowKeyboardService.activeViewForTest?.isShown == true
        }
        val bar = KeyFlowKeyboardService.activeViewForTest!!.barViewsForTest.first { it.isShown }
        waitUntil("BarView ainda sem geometria medida") { bar.layoutForTest.buttons.isNotEmpty() }
        return bar
    }

    private fun indexOf(
        bar: BarView,
        predicate: (com.keyflow.ime.bars.BarGeometry.PlacedButton) -> Boolean,
    ): Int = bar.layoutForTest.buttons.indexOfFirst(predicate)

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
        const val KEY_BARS = "kbd_bars"
    }
}
