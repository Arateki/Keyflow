package com.keyflow.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
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
import com.keyflow.ime.keyboard.layout.KeyAction as CanvasKeyAction
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * Instrumentado da grade Canvas no IME **real** (KC-T6/T10, padrão único desde a promoção): tocar a
 * grade comita texto pelo mesmo `handleKey`, e o mic (SYNC-01..04) segue intacto.
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardCanvasGridTest {

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
        // KeyFlowController é singleton de processo (sobrevive ao fim deste teste, dentro do mesmo gate).
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun typingCommitsTextThroughTheSameHandleKey() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest
        assertNotNull("a grade Canvas deveria estar presente", grid)

        tapKey(grid!!, "q")

        waitUntil("o campo não recebeu \"q\" digitado pela grade") {
            device.findObject(By.desc("ime_probe_field"))?.text?.contains("q") == true
        }
    }

    /** Num toque rápido o dedo escorrega uns pixels para a vizinha: a tecla não pode sumir por isso. */
    @Test
    fun aSmallDriftIntoTheNeighborStillTypesTheKeyTouched() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!
        val q = keyOnScreen(grid, "q")

        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, q.centerX, q.centerY, down)
        inject(MotionEvent.ACTION_UP, q.right + q.width / 10, q.centerY, down)

        waitUntil("o \"q\" tocado se perdeu com o dedo escorregando para o \"w\"") { fieldText() == "q" }
    }

    /** Arrastar até outra tecla antes de soltar digita a tecla onde o dedo saiu. */
    @Test
    fun slidingToAnotherKeyTypesTheKeyUnderTheFinger() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!
        val q = keyOnScreen(grid, "q")
        val e = keyOnScreen(grid, "e")

        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, q.centerX, q.centerY, down)
        inject(MotionEvent.ACTION_MOVE, e.centerX, e.centerY, down)
        inject(MotionEvent.ACTION_UP, e.centerX, e.centerY, down)

        waitUntil("arrastar do \"q\" até o \"e\" deveria digitar \"e\"") { fieldText() == "e" }
    }

    /**
     * Dois polegares: o 2º dedo encosta antes de o 1º soltar e solta primeiro. As letras saem na ordem em
     * que foram tocadas — a do 1º dedo sai quando o 2º encosta.
     */
    @Test
    fun rolloverKeepsTheOrderTheKeysWereTouched() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!
        val q = keyOnScreen(grid, "q")
        val p = keyOnScreen(grid, "p")

        val down = SystemClock.uptimeMillis()
        injectPointers(MotionEvent.ACTION_DOWN, down, listOf(q.centerX to q.centerY))
        injectPointers(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), down, listOf(q.centerX to q.centerY, p.centerX to p.centerY))
        injectPointers(pointerAction(MotionEvent.ACTION_POINTER_UP, 1), down, listOf(q.centerX to q.centerY, p.centerX to p.centerY))
        injectPointers(MotionEvent.ACTION_UP, down, listOf(q.centerX to q.centerY))

        waitUntil("dois dedos sobrepostos deveriam digitar \"qp\", na ordem do toque") { fieldText() == "qp" }
    }

    /** Segurar uma tecla com acentos abre a tira com a 1ª variante já escolhida; soltar sem mexer insere ela. */
    @Test
    fun holdingAKeyAndReleasingInPlaceTypesTheFirstAccent() = withPortugueseKeyboard {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!
        val u = keyOnScreen(grid, "u")

        val down = SystemClock.uptimeMillis()
        injectDown(u.centerX, u.centerY, down)
        waitUntil({ "a tira de acentos do \"u\" não abriu (${keyboardState(grid, u)})" }) { grid.accentVariantsForTest != null }
        val variants = grid.accentVariantsForTest!!
        inject(MotionEvent.ACTION_UP, u.centerX, u.centerY, down)

        waitUntil("soltar sem mexer deveria inserir \"${variants[0]}\"") { fieldText() == variants[0] }
    }

    /**
     * Como no Samsung: com a tira aberta, andar para o lado na altura da própria tecla — sem subir até a
     * tira — troca a escolha, e soltar insere a escolhida.
     */
    @Test
    fun slidingSidewaysOnTheKeyPicksTheNextAccent() = withPortugueseKeyboard {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!
        val u = keyOnScreen(grid, "u")

        val down = SystemClock.uptimeMillis()
        injectDown(u.centerX, u.centerY, down)
        waitUntil({ "a tira de acentos do \"u\" não abriu (${keyboardState(grid, u)})" }) { grid.accentVariantsForTest != null }
        val variants = grid.accentVariantsForTest!!
        val nextX = u.centerX + grid.accentChipWidthForTest!!.toInt()
        inject(MotionEvent.ACTION_MOVE, nextX, u.centerY, down)
        inject(MotionEvent.ACTION_UP, nextX, u.centerY, down)

        waitUntil("andar um chip para o lado deveria inserir \"${variants[1]}\"") { fieldText() == variants[1] }
    }

    @Test
    fun canvasGridDoesNotDisturbTheMicButton() {
        launchProbe()

        // SYNC-01/02: o tap no mic ainda inicia a gravação. Com as barras como padrão (ROL-02) o mic é
        // desenhado em Canvas e não é mais um TextView, então é o nó de acessibilidade da BarView que o
        // expõe — o `By.text` continua valendo enquanto a barra antiga existir.
        (device.findObject(By.desc("🎙")) ?: device.findObject(By.text("🎙")))?.click()
        device.wait(Until.hasObject(By.textContains("●")), TIMEOUT)
        waitUntil("gravação não iniciou com a grade Canvas ativa") {
            KeyFlowController.buttonState.value == ButtonState.RECORDING
        }
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
        awaitKeyboardAttached(device) { context.startActivity(intent) }
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

    private class KeyOnScreen(val left: Int, val right: Int, val centerX: Int, val centerY: Int) {
        val width: Int get() = right - left
    }

    private fun keyOnScreen(grid: com.keyflow.ime.keyboard.KeyboardGridView, label: String): KeyOnScreen {
        waitUntil("grade Canvas ainda sem geometria medida") { grid.placedRowsForTest.isNotEmpty() }
        val placed = grid.placedRowsForTest.flatMap { it.keys }
            .first { (it.keyDef.action as? CanvasKeyAction.Char)?.text == label }
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        return KeyOnScreen(
            left = (loc[0] + placed.left).toInt(),
            right = (loc[0] + placed.right).toInt(),
            centerX = (loc[0] + (placed.left + placed.right) / 2).toInt(),
            centerY = (loc[1] + (placed.top + placed.bottom) / 2).toInt(),
        )
    }

    private fun fieldText(): String? = device.findObject(By.desc("ime_probe_field"))?.text

    /** Acentos existem em pt (não em en): fixa pt durante [block] e devolve o idioma que o aparelho tinha. */
    private fun withPortugueseKeyboard(block: () -> Unit) {
        val repo = SettingsRepository(context)
        val original = runBlocking { repo.keyboardLanguageOnce() }
        runBlocking { repo.saveKeyboardLanguageOnce("pt") }
        try {
            block()
        } finally {
            runBlocking { repo.saveKeyboardLanguageOnce(original) }
        }
    }

    private fun pointerAction(action: Int, pointerIndex: Int): Int =
        action or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** Toque com vários dedos (ids 0, 1, … na ordem de [points]) — mesma recusa tratada que [inject]. */
    private fun injectPointers(action: Int, downTime: Long, points: List<Pair<Int, Int>>): Boolean {
        val properties = points.indices.map { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coords = points.map { (x, y) ->
            MotionEvent.PointerCoords().apply {
                this.x = x.toFloat()
                this.y = y.toFloat()
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, points.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        return try {
            instrumentation.sendPointerSync(event)
            true
        } catch (e: IllegalArgumentException) {
            false
        } finally {
            event.recycle()
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
    /**
     * DOWN que a plataforma aceitou (ver [inject]). Segurar a tecla depende de um único DOWN: recusado, a tira de
     * acentos nunca abre, e o teste esperava 5 s por nada (CI, 2026-10-09 — sem log, ficou sem prova de que foi isto;
     * agora a recusa vai para o logcat).
     */
    private fun injectDown(x: Int, y: Int, time: Long) {
        waitUntil("a plataforma recusou o toque em ($x, $y)") {
            inject(MotionEvent.ACTION_DOWN, x, y, time).also { accepted ->
                if (!accepted) Log.w("KeyFlow.CanvasGridTest", "injeção do DOWN recusada em ($x, $y); tentando de novo")
            }
        }
    }

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

    private fun waitUntil(message: String, timeoutMs: Long = TIMEOUT, condition: () -> Boolean) =
        waitUntil({ message }, timeoutMs, condition)

    private fun waitUntil(message: () -> String, timeoutMs: Long = TIMEOUT, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message())
    }

    /** O que a grade e o teclado diziam na hora da falha — para a próxima falha dizer por quê. */
    private fun keyboardState(grid: com.keyflow.ime.keyboard.KeyboardGridView, key: KeyOnScreen): String {
        val view = KeyFlowKeyboardService.activeViewForTest
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        val language = runBlocking { SettingsRepository(context).keyboardLanguageOnce() }
        return "toque em (${key.centerX}, ${key.centerY}); grade em (${loc[0]}, ${loc[1]}) ${grid.width}x${grid.height}, " +
            "visível=${grid.isShown}, mesma view ativa=${view?.canvasGridForTest === grid}, expandido=${view?.expandedForTest}, " +
            "camada=${grid.currentLayerIdForTest}, fileiras=${grid.placedRowsForTest.size}, idioma salvo=$language"
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
