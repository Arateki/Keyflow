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
import com.keyflow.core.shortcuts.GridCell
import com.keyflow.core.shortcuts.GridTriggerMode
import com.keyflow.core.shortcuts.PhysicalKeyMap
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.data.SettingsRepository
import android.view.KeyEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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

    @Test
    fun gridTriggerDragAnchorsLensAndReleaseEmitsTheCellShortcut() {
        launchWithTestGrid()
        val bar = KeyFlowKeyboardService.activeViewForTest!!.barViewsForTest.first { it.isShown }
        waitUntil("BarView ainda sem geometria medida") { bar.layoutForTest.buttons.isNotEmpty() }
        val trigger = bar.layoutForTest.buttons.first { it.button is BarButton.GridTrigger }
        val rect = bar.buttonScreenRectForTest(trigger.index)

        // Press → arrasto pra BAIXO (ancora a grade abaixo do dedo, GRD-01) → lente visível (GRD-02).
        // Pra baixo de propósito: eventos INJETADOS acima da janela do IME chegam grampeados no frame
        // (mesmo desalinhamento que o KeyFlowKeyboardSelectorTest contorna com folga) — toque real não
        // sofre disso (o seletor do mic acima da barra funciona no device); a âncora pra cima fica na
        // validação manual (SB-T10). A grade pra baixo cai sobre a área do QWERTY, dentro da janela.
        val downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), downTime, downTime)
        inject(MotionEvent.ACTION_MOVE, rect.centerX(), rect.centerY() + 120, downTime)
        waitUntil("grade não ancorou/abriu a lente") { bar.gridPopupShowingForTest }
        val frame = bar.gridFrameForTest!!

        // Desliza até o centro da célula 0 (linha 0, coluna 0 — atalho "c") e solta (GRD-04).
        val cellX = (frame.left + frame.width / 4).toInt()
        val cellY = (frame.top + frame.height / 2).toInt()
        inject(MotionEvent.ACTION_MOVE, cellX, cellY, downTime)
        inject(MotionEvent.ACTION_UP, cellX, cellY, downTime)

        waitUntil("o campo não recebeu o atalho da célula (KeyEvent físico \"c\")") {
            device.findObject(By.desc("ime_probe_field"))?.text?.contains("c") == true
        }
    }

    @Test
    fun gridReleaseOutsideTheFrameCancelsWithoutEmitting() {
        launchWithTestGrid()
        val bar = KeyFlowKeyboardService.activeViewForTest!!.barViewsForTest.first { it.isShown }
        waitUntil("BarView ainda sem geometria medida") { bar.layoutForTest.buttons.isNotEmpty() }
        val trigger = bar.layoutForTest.buttons.first { it.button is BarButton.GridTrigger }
        val rect = bar.buttonScreenRectForTest(trigger.index)

        val downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), downTime, downTime)
        inject(MotionEvent.ACTION_MOVE, rect.centerX(), rect.centerY() + 120, downTime)
        waitUntil("grade não ancorou") { bar.gridFrameForTest != null }
        val frame = bar.gridFrameForTest!!

        // Solta fora do retângulo da grade, ainda dentro da janela do IME (GRD-05): nada é emitido.
        val outsideX = (frame.right + 60).toInt().coerceAtMost(context.resources.displayMetrics.widthPixels - 4)
        val outsideY = (frame.top + 20).toInt()
        inject(MotionEvent.ACTION_MOVE, outsideX, outsideY, downTime)
        inject(MotionEvent.ACTION_UP, outsideX, outsideY, downTime)

        Thread.sleep(600)
        val text = device.findObject(By.desc("ime_probe_field"))?.text.orEmpty()
        assertTrue("cancelamento emitiu \"$text\" no campo", !text.contains("c"))
    }

    @Test
    fun joystickPullFiresArrowAndHoldingRepeatsWithAcceleration() {
        val config = BarsConfig(
            mirrored = true,
            collapsed = listOf(
                BarDef(listOf(BarButton.Mic, BarButton.Joystick, BarButton.ToggleExpand))
            ),
            expanded = emptyList(),
        )
        runBlocking { SettingsRepository(context).saveBarsConfig(config) }
        launchProbe(bars = true)
        val bar = KeyFlowKeyboardService.activeViewForTest!!.barViewsForTest.first { it.isShown }
        waitUntil("BarView ainda sem geometria medida") { bar.layoutForTest.buttons.isNotEmpty() }
        val stick = bar.layoutForTest.buttons.first { it.button == BarButton.Joystick }
        val rect = bar.buttonScreenRectForTest(stick.index)

        // Puxa pra direita além do raio-morto e SEGURA: 1º disparo imediato (JOY-01) + repeats
        // acelerando (JOY-02). O probe converte DPAD_RIGHT em "→" visível.
        val downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), downTime, downTime)
        inject(MotionEvent.ACTION_MOVE, rect.centerX() + 60, rect.centerY(), downTime)
        Thread.sleep(900) // repeat: 1º em ~400ms, 2º em ~760ms (RepeatSchedule)
        inject(MotionEvent.ACTION_UP, rect.centerX() + 60, rect.centerY(), downTime)

        waitUntil("alavanca não emitiu setas repetidas") {
            val text = device.findObject(By.desc("ime_probe_field"))?.text.orEmpty()
            text.count { it == '→' } >= 2
        }

        // Soltar parou (JOY-04): a contagem congela.
        Thread.sleep(600)
        val frozen = device.findObject(By.desc("ime_probe_field"))?.text.orEmpty().count { it == '→' }
        Thread.sleep(500)
        val after = device.findObject(By.desc("ime_probe_field"))?.text.orEmpty().count { it == '→' }
        assertEquals("repeat continuou após soltar", frozen, after)
    }

    /** Sobe o probe com uma grade 1×2 (célula 0 = "c", célula 1 vazia) e um gatilho OPEN_ONLY na barra. */
    private fun launchWithTestGrid() {
        val grid = ShortcutGrid(
            id = "test",
            name = "teste",
            rows = 1,
            cols = 2,
            cells = mapOf(0 to GridCell(PhysicalKeyMap.key(KeyEvent.KEYCODE_C))),
        )
        val config = BarsConfig(
            mirrored = true,
            collapsed = listOf(
                BarDef(
                    listOf(
                        BarButton.Mic,
                        BarButton.GridTrigger(gridId = "test", mode = GridTriggerMode.OPEN_ONLY),
                        BarButton.ToggleExpand,
                    )
                )
            ),
            expanded = emptyList(),
        )
        runBlocking {
            SettingsRepository(context).saveShortcutGrids(listOf(grid))
            SettingsRepository(context).saveBarsConfig(config)
        }
        launchProbe(bars = true)
    }

    // ── ações de alto nível (mesmo harness do KeyFlowKeyboardCanvasGridTest) ──

    private fun launchProbe(bars: Boolean) {
        uiPrefs.edit().putBoolean(KEY_BARS, bars).apply()
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

    /**
     * Injeta um toque, devolvendo `false` se a plataforma RECUSAR a injeção
     * (`IllegalArgumentException: Targeted input event injection ... was not directed at a window owned
     * by uid N`). `sendPointerSync` só entrega em janela do próprio app, e logo depois de reinstalar o
     * APK a janela do IME ainda não assumiu aquela região da tela. Medido em 2026-09-08: as duas
     * primeiras rodadas após um `installDebug` falhavam assim. Sem o `catch`, a exceção escapava dos
     * laços de retry e reprovava o teste na hora.
     */
    private fun inject(action: Int, x: Int, y: Int, downTime: Long, eventTime: Long = SystemClock.uptimeMillis()): Boolean {
        val event = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try {
            instrumentation.sendPointerSync(event)
            true
        } catch (e: IllegalArgumentException) {
            false
        } finally {
            event.recycle()
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
