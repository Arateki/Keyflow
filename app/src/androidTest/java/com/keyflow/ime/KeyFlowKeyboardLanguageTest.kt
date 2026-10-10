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
import com.keyflow.data.SettingsRepository
import com.keyflow.ime.keyboard.KeyboardGridView
import com.keyflow.ime.keyboard.layout.KeyAction as CanvasKeyAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado do idioma da grade Canvas (pedido do Yan, 2026-07-02 — reverte o KC-T9): o idioma agora é
 * gerenciado **100% pelo app**, não pelos subtypes do sistema Android (declarar >1 subtype fazia o
 * seletor "Trocar teclado" do sistema listar "KeyFlow" repetido, um por idioma, e a troca dependia da
 * tela de Idiomas do Android). O globo cicla `Settings.keyboardLanguage` (pt→en→es→pt) sozinho, sempre
 * visível — não depende mais de quantos subtypes estão habilitados no sistema.
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardLanguageTest {

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
        // Devolve o idioma real do app ao padrão — este teste roda contra o DataStore de produção (é a
        // única forma de observar o que o KeyFlowKeyboardService real persistiu de verdade).
        runBlocking { SettingsRepository(context).saveKeyboardLanguageOnce("pt") }
        userSettings.restore()
        device.pressHome()
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun gridLettersLayerHasNoEnyeWhenDefaultLanguageIsPortuguese() {
        val grid = launchProbeAndGetGrid()

        val labels = grid.placedRowsForTest.flatMap { it.keys }.map { it.keyDef.label }

        assertFalse("grade não deveria ter ñ no idioma padrão (pt)", labels.contains("ñ"))
    }

    @Test
    fun globeKeyIsAlwaysPresentRegardlessOfSystemSubtypes() {
        // Diferente do KC-T9 antigo: não depende mais de quantos subtypes o sistema tem habilitados —
        // o idioma é gerenciado pelo app, então o globo sempre tem pra onde ciclar (pt→en→es→pt).
        val grid = launchProbeAndGetGrid()
        val hasGlobe = grid.placedRowsForTest.flatMap { it.keys }.any { it.keyDef.action == CanvasKeyAction.Language }

        assertTrue("globo deveria estar sempre presente (idioma é gerenciado pelo app)", hasGlobe)
    }

    @Test
    fun tappingTheGlobeCyclesTheLanguageAndPersistsIt() {
        val repo = SettingsRepository(context)
        var grid = launchProbeAndGetGrid()

        tapGlobe(grid) // pt → en
        waitUntil("idioma não persistiu como 'en'") { runBlocking { repo.keyboardLanguageOnce() } == "en" }
        grid = waitForNewGrid(grid)

        tapGlobe(grid) // en → es
        waitUntil("idioma não persistiu como 'es'") { runBlocking { repo.keyboardLanguageOnce() } == "es" }
        grid = waitForNewGrid(grid)

        val labels = grid.placedRowsForTest.flatMap { it.keys }.map { it.keyDef.label }
        assertTrue("grade deveria ter ñ depois de ciclar até ES", labels.contains("ñ"))
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
        // Nunca acha o "🎙" (o mic é desenhado pela BarView): na prática são 5 s para a entrada do teclado assentar. Tirá-los
        // rendeu 4 falhas de teclado em 2 rodadas do CI (2026-10-10). Trocar só por uma condição que espere o mesmo assentamento.
        device.wait(Until.hasObject(By.text("🎙")), TIMEOUT)
        awaitKeyboardAttached(device) { context.startActivity(intent) }
        waitUntil("grade Canvas não montou") { KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest != null }
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!
        waitUntil("grade Canvas ainda sem geometria medida") { grid.placedRowsForTest.isNotEmpty() }
        return grid
    }

    /** Toca a tecla globo (KeyAction.Language) na geometria real da grade. */
    private fun tapGlobe(grid: KeyboardGridView) {
        val placed = grid.placedRowsForTest.flatMap { it.keys }.first { it.keyDef.action == CanvasKeyAction.Language }
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        val x = (loc[0] + (placed.left + placed.right) / 2).toInt()
        val y = (loc[1] + (placed.top + placed.bottom) / 2).toInt()
        val time = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, x, y, time)
        inject(MotionEvent.ACTION_UP, x, y, SystemClock.uptimeMillis())
    }

    /**
     * O globo reconstrói a view inteira (`setInputView(onCreateInputView())`, mesmo caminho da antiga
     * troca de subtype) — espera uma instância de grade DIFERENTE da anterior aparecer, com geometria já
     * medida.
     */
    private fun waitForNewGrid(previous: KeyboardGridView): KeyboardGridView {
        waitUntil("grade não foi recriada após o globo") {
            KeyFlowKeyboardService.activeViewForTest?.canvasGridForTest?.let { it !== previous } == true
        }
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!
        waitUntil("grade nova ainda sem geometria medida") { grid.placedRowsForTest.isNotEmpty() }
        return grid
    }

    /**
     * Injeta um toque, devolvendo `false` se a plataforma RECUSAR a injeção
     * (`IllegalArgumentException: Targeted input event injection ... was not directed at a window owned
     * by uid N`). `sendPointerSync` só entrega em janela do próprio app, e logo depois de reinstalar o
     * APK a janela do IME ainda não assumiu aquela região da tela. Medido em 2026-09-08: as duas
     * primeiras rodadas após um `installDebug` falhavam assim. Sem o `catch`, a exceção escapava dos
     * laços de retry e reprovava o teste na hora.
     */
    private fun inject(action: Int, x: Int, y: Int, time: Long): Boolean {
        val event = MotionEvent.obtain(time, time, action, x.toFloat(), y.toFloat(), 0).apply {
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
    }
}
