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
import com.keyflow.core.shortcuts.PhysicalKeyMap
import com.keyflow.data.SettingsRepository
import android.view.KeyEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado das barras Canvas no IME real (SB-T5): com a flag `kbd_bars` ligada, a pilha de
 * `BarView` substitui a barra antiga (BAR-01/02), o mic segue sincronizado (SYNC-01) e uma **tecla
 * avulsa** emite KeyEvent físico de verdade no campo (EMT-02 fim-a-fim). Flag desligada = barra antiga
 * intocada (ROL-02).
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardBarsTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val uiPrefs get() = context.getSharedPreferences("keyflow_kb_ui", Context.MODE_PRIVATE)

    @After
    fun tearDown() {
        uiPrefs.edit().remove(KEY_BARS).remove(KEY_CANVAS_GRID).apply()
        runBlocking { SettingsRepository(context).saveBarsConfig(BarsConfig.DEFAULT) }
        shell("ime reset")
        device.pressHome()
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun flagOffKeepsTheLegacyBar() {
        launchProbe(bars = false)

        assertTrue(KeyFlowKeyboardService.activeViewForTest!!.barViewsForTest.isEmpty())
    }

    @Test
    fun flagOnRendersBarViewsWithFactoryParityAndLiveMic() {
        launchProbe(bars = true)
        val view = KeyFlowKeyboardService.activeViewForTest!!

        // BAR-01/02: pilha de BarView presente, com o mic renderizado em Canvas no estado atual.
        assertTrue(view.barViewsForTest.isNotEmpty())
        assertEquals("🎙", view.micLabelForTest)

        // SYNC-01: estado do mic roteia para a BarView (timer/estado, mesmo caminho do controller).
        instrumentation.runOnMainSync {
            view.updateMicState(ButtonState.SENDING)
        }
        assertEquals("···", view.micLabelForTest)
        instrumentation.runOnMainSync { view.updateMicState(ButtonState.IDLE_NO_FIELD) }
    }

    @Test
    fun singleKeyButtonEmitsARealPhysicalKeyEventIntoTheField() {
        // Barra custom: mic + tecla avulsa "b" + colapsar; espelhada (vale pros dois modos).
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

        launchProbe(bars = true)
        val view = KeyFlowKeyboardService.activeViewForTest!!
        val bar = view.barViewsForTest.first { it.isShown }
        waitUntil("BarView ainda sem geometria medida") { bar.layoutForTest.buttons.isNotEmpty() }

        val single = bar.layoutForTest.buttons.first { it.button is BarButton.SingleKey }
        val rect = bar.buttonScreenRectForTest(single.index)
        val time = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), time)
        inject(MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), SystemClock.uptimeMillis())

        // EMT-02 fim-a-fim: o EditText do probe interpreta KEYCODE_B físico como o caractere "b".
        waitUntil("o campo não recebeu o KeyEvent físico da tecla avulsa") {
            device.findObject(By.desc("ime_probe_field"))?.text?.contains("b") == true
        }
    }

    // ── ações de alto nível (mesmo harness do KeyFlowKeyboardCanvasGridTest) ──

    private fun launchProbe(bars: Boolean) {
        // Canvas ligado junto: as barras no expandido só existem no modo Canvas (o teclado antigo de
        // TextView fica fora do redesign — decisão de design da SB-T5).
        uiPrefs.edit().putBoolean(KEY_CANVAS_GRID, true).putBoolean(KEY_BARS, bars).apply()
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
        // A view precisa estar de fato na tela antes de medirmos retângulos de botões.
        waitUntil("KeyFlowKeyboardView não ficou visível") {
            KeyFlowKeyboardService.activeViewForTest?.isShown == true
        }
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
        const val KEY_BARS = "kbd_bars"
    }
}
