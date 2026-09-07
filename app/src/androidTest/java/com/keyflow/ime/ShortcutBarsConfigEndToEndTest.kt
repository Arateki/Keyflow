package com.keyflow.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.data.SettingsRepository
import com.keyflow.ui.config.BARS_EDITOR_CATALOG_GRID_TAG
import com.keyflow.ui.config.BARS_EDITOR_CATALOG_JOYSTICK_TAG
import com.keyflow.ui.config.BARS_EDITOR_CATALOG_SINGLE_KEY_TAG
import com.keyflow.ui.config.BARS_EDITOR_GRID_FORM_CONFIRM_TAG
import com.keyflow.ui.config.BARS_EDITOR_MIRROR_TOGGLE_TAG
import com.keyflow.ui.config.BarsEditor
import com.keyflow.ui.config.GRIDS_EDITOR_ADD_TAG
import com.keyflow.ui.config.GRIDS_EDITOR_SHEET_CLOSE_TAG
import com.keyflow.ui.config.GridsEditor
import com.keyflow.ui.config.barAddButtonTag
import com.keyflow.ui.config.gridCellTag
import com.keyflow.ui.config.gridRowTag
import com.keyflow.ui.config.physicalKeyTag
import com.keyflow.ui.theme.KeyFlowTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SB-T9 (Done when): monta uma barra custom completa (grade+alavanca+2 teclas avulsas) só pela UI
 * ([BarsEditor]/[GridsEditor]), persiste via [SettingsRepository] de verdade e confirma que ela aparece
 * no teclado real (flag `kbd_bars` ligada) — mesmo harness do [KeyFlowKeyboardBarsTest].
 */
@RunWith(AndroidJUnit4::class)
class ShortcutBarsConfigEndToEndTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule
    val rule = createComposeRule()

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
    }

    private inner class Harness {
        var barsConfig by mutableStateOf(
            BarsConfig(mirrored = false, collapsed = listOf(BarDef(emptyList())), expanded = listOf(BarDef(emptyList()))),
        )
        var grids by mutableStateOf<List<ShortcutGrid>>(emptyList())

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                Column {
                    BarsEditor(barsConfig = barsConfig, onBarsConfigChange = { barsConfig = it }, grids = grids)
                    GridsEditor(grids = grids, onGridsChange = { grids = it }, referencedGridIds = barsConfig.referencedGridIds())
                }
            }
        }
    }

    @Test
    fun assemblingACustomBarPurelyViaTheUiPersistsAndShowsOnTheRealKeyboard() {
        val h = Harness()
        rule.setContent { h.Content() }

        // 1) Cria uma grade (3x3 default) e atribui "c" na célula (0,0).
        rule.onNodeWithTag(GRIDS_EDITOR_ADD_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(gridRowTag(h.grids.first().id)).performClick()
        rule.onNodeWithTag(gridCellTag(0, 0)).performClick()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_C)).performClick()
        rule.onNodeWithTag(GRIDS_EDITOR_SHEET_CLOSE_TAG).performClick()

        // 2) Barra: gatilho de grade (aponta pra grade recém-criada por padrão) + confirma.
        rule.onNodeWithTag(barAddButtonTag(0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_CATALOG_GRID_TAG).performClick()
        rule.onNodeWithTag(BARS_EDITOR_GRID_FORM_CONFIRM_TAG).performClick()

        // 3) Alavanca.
        rule.onNodeWithTag(barAddButtonTag(0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_CATALOG_JOYSTICK_TAG).performClick()

        // 4) Duas teclas avulsas: "b" e "m".
        rule.onNodeWithTag(barAddButtonTag(0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_CATALOG_SINGLE_KEY_TAG).performClick()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_B)).performClick()

        rule.onNodeWithTag(barAddButtonTag(0)).performClick()
        rule.onNodeWithTag(BARS_EDITOR_CATALOG_SINGLE_KEY_TAG).performClick()
        rule.onNodeWithTag(physicalKeyTag(KeyEvent.KEYCODE_M)).performClick()

        // Espelha pro modo expandido também — o teclado real abre expandido por padrão
        // (`preferExpanded`), e sem isso o outro modo ficaria com a barra vazia do harness.
        rule.onNodeWithTag(BARS_EDITOR_MIRROR_TOGGLE_TAG).performClick()

        rule.waitForIdle()
        val finalConfig = h.barsConfig
        val finalGrids = h.grids

        // Sanidade da montagem (antes de cruzar pro IME real): 4 botões na barra principal, espelhados.
        assertTrue(finalConfig.mirrored)
        val buttons = finalConfig.collapsed[0].buttons
        assertEquals(4, buttons.size)
        assertEquals(buttons, finalConfig.expanded[0].buttons)
        assertTrue(buttons[0] is BarButton.GridTrigger)
        assertEquals(finalGrids.first().id, (buttons[0] as BarButton.GridTrigger).gridId)
        assertTrue(buttons[1] is BarButton.Joystick)
        assertEquals(KeyEvent.KEYCODE_B, (buttons[2] as BarButton.SingleKey).key.keyCode)
        assertEquals(KeyEvent.KEYCODE_M, (buttons[3] as BarButton.SingleKey).key.keyCode)

        // Persiste de verdade (mesmo DataStore que o SetupActivity/IME leem) — prova o round-trip, não só
        // o estado em memória do harness.
        runBlocking {
            SettingsRepository(context).saveBarsConfig(finalConfig)
            SettingsRepository(context).saveShortcutGrids(finalGrids)
        }

        // Cruza pro teclado REAL (flag kbd_bars ligada) — a config montada só pela UI deve aparecer lá.
        launchProbe()
        val bar = KeyFlowKeyboardService.activeViewForTest!!.barViewsForTest.first { it.isShown }
        waitUntil("BarView ainda sem geometria medida") { bar.layoutForTest.buttons.isNotEmpty() }

        val realButtons = bar.layoutForTest.buttons.map { it.button }
        assertTrue("gatilho de grade não apareceu no teclado real", realButtons.any { it is BarButton.GridTrigger })
        assertTrue("alavanca não apareceu no teclado real", realButtons.any { it is BarButton.Joystick })
        assertTrue(
            "teclas avulsas não apareceram no teclado real",
            realButtons.count { it is BarButton.SingleKey } == 2,
        )
        val trigger = realButtons.filterIsInstance<BarButton.GridTrigger>().first()
        assertTrue(
            "gatilho de grade não deveria estar órfão (a grade existe)",
            trigger.gridId in finalGrids.map { it.id },
        )
    }

    private fun launchProbe() {
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
        waitUntil("KeyFlowKeyboardView não anexou") { KeyFlowKeyboardService.activeViewForTest != null }
        waitUntil("KeyFlowKeyboardView não ficou visível") {
            KeyFlowKeyboardService.activeViewForTest?.isShown == true
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
        const val KEY_BARS = "kbd_bars"
    }
}
