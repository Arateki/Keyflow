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
import com.keyflow.data.SettingsRepository
import com.keyflow.ime.keyboard.KeyboardGridView
import com.keyflow.ui.ImeProbeActivity
import com.keyflow.ime.keyboard.layout.KeyAction as CanvasKeyAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado da composição + corretor do sistema no IME **real** (KC-T7/SPL-01..03): digitar letras
 * na grade Canvas compõe (`setComposingText`) em vez de comitar direto; separador/backspace-até-vazio
 * encerram a composição corretamente; a quantidade/conteúdo exato das sugestões do corretor depende do
 * pacote instalado no device e é validada **manualmente** (esta suíte só garante a mecânica, sem crash).
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardSpellCheckerTest {

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
    fun typingLettersComposesTheWordInsteadOfCommittingEachLetter() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!

        performUntil("o") { tapKey(grid, "o") }
        performUntil("oi") { tapKey(grid, "i") }
    }

    @Test
    fun spaceFinishesComposingAndCommitsANormalSpace() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!

        performUntil("o") { tapKey(grid, "o") }
        performUntil("oi") { tapKey(grid, "i") }
        // Vírgula (4ª fileira da grade, KC-T10): também precisa encerrar a composição.
        performUntil("oi,") { tapKey(grid, ",") }
    }

    @Test
    fun backspaceDownToEmptyClearsTheComposedLetterWithoutDeletingBefore() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!

        performUntil("o") { tapKey(grid, "o") }
        performUntil("o,") { tapKey(grid, ",") } // texto prévio antes da nova palavra
        performUntil("o,a") { tapKey(grid, "a") }
        performUntil("o,") { tapBackspaceOnGrid(grid) }
    }

    /**
     * Levar o cursor para o meio do texto e digitar: a letra entra onde o cursor está. Antes, a palavra
     * que estava em composição seguia "do teclado" no campo, e a letra seguinte a SUBSTITUÍA lá atrás
     * (relato do Yan, 2026-09-27: "as teclas saem do lugar que deveriam").
     */
    @Test
    fun typingAfterMovingTheCursorInsertsWhereTheCursorIs() {
        launchProbe()
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!

        performUntil("o") { tapKey(grid, "o") }
        performUntil("oi") { tapKey(grid, "i") }
        waitUntil("o teclado não soube do cursor no fim de \"oi\"") { KeyFlowKeyboardService.selectionStartForTest == 2 }
        ImeProbeActivity.moveCursorForTest(1)
        // O aviso de seleção chega ao teclado de forma assíncrona: antes eram 500 ms fixos, que no CI não bastaram (2026-10-10).
        waitUntil("o teclado não soube do cursor movido") { KeyFlowKeyboardService.selectionStartForTest == 1 }
        tapKey(grid, "a")

        waitUntil("a letra deveria entrar no cursor (\"oai\")") { fieldText() == "oai" }
    }

    /** Q9: "nao" + espaço vira "não " pelo dicionário próprio; o backspace logo em seguida desfaz a troca. */
    @Test
    fun spaceAppliesTheCorrectionAndBackspaceRightAfterUndoesIt() = withPortugueseKeyboard {
        launchProbe()
        waitUntil("o dicionário de português não carregou") { KeyFlowKeyboardService.suggesterReadyForTest }
        val grid = KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest!!

        performUntil("n") { tapKey(grid, "n") }
        performUntil("na") { tapKey(grid, "a") }
        performUntil("nao") { tapKey(grid, "o") }
        tapAction(grid, CanvasKeyAction.Space)
        waitUntil("espaço deveria trocar \"nao\" por \"não\"") { fieldText() == "não " }

        tapBackspaceOnGrid(grid)
        waitUntil("backspace logo depois deveria voltar a \"nao\"") { fieldText() == "nao" }
    }

    /** Q9: a sugestão tocada SUBSTITUI a palavra em composição (antes era colada depois dela). */
    @Test
    fun tappingASuggestionReplacesTheWordBeingTyped() = withPortugueseKeyboard {
        val withCandidates = BarsConfig(
            mirrored = true,
            collapsed = listOf(BarDef(listOf(BarButton.Mic, BarButton.Candidates, BarButton.ToggleExpand))),
            expanded = listOf(BarDef(listOf(BarButton.Mic, BarButton.Candidates, BarButton.ToggleExpand))),
        )
        runBlocking { SettingsRepository(context).saveBarsConfig(withCandidates) }
        launchProbe()
        waitUntil("o dicionário de português não carregou") { KeyFlowKeyboardService.suggesterReadyForTest }
        val view = KeyFlowKeyboardService.activeViewForTest!!
        val grid = view.canvasGridForTest!!

        performUntil("v") { tapKey(grid, "v") }
        performUntil("vo") { tapKey(grid, "o") }
        performUntil("voc") { tapKey(grid, "c") }
        performUntil("voce") { tapKey(grid, "e") }
        waitUntil("a faixa deveria sugerir \"você\"") { view.candidatesViewForTest?.candidatesForTest?.contains("você") == true }
        instrumentation.runOnMainSync { view.candidatesViewForTest!!.tapForTest("você") }

        waitUntil("tocar em \"você\" deveria deixar \"você \" no campo") { fieldText() == "você " }
    }

    @Test
    fun candidatesViewExistsAndDoesNotCrashWhenEmpty() {
        // A faixa só existe se ALGUMA barra declarar o slot `Candidates` (BarView), e isso é config de
        // quem usa: no A32 do Yan nenhuma barra o declarava e este teste caía sem haver defeito nenhum
        // (2026-09-08). Monta a config que o teste realmente exige; o `UserSettingsGuard` devolve a dele.
        val withCandidates = BarsConfig(
            mirrored = true,
            collapsed = listOf(BarDef(listOf(BarButton.Mic, BarButton.Candidates, BarButton.ToggleExpand))),
            expanded = emptyList(),
        )
        runBlocking { SettingsRepository(context).saveBarsConfig(withCandidates) }

        launchProbe()
        val view = KeyFlowKeyboardService.activeViewForTest!!
        assertNotNull("com o slot Candidates na barra a faixa deveria existir", view.candidatesViewForTest)
        assertTrue(view.candidatesViewForTest!!.candidatesForTest.isEmpty())
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
        // Nunca acha o "🎙" (o mic é desenhado pela BarView): na prática são 5 s para a entrada do teclado assentar. Tirá-los
        // rendeu 4 falhas de teclado em 2 rodadas do CI (2026-10-10). Trocar só por uma condição que espere o mesmo assentamento.
        device.wait(Until.hasObject(By.text("🎙")), TIMEOUT)
        awaitKeyboardAttached(device) { context.startActivity(intent) }
        waitUntil("grade Canvas não montou") { KeyFlowKeyboardService.activeViewForTest!!.canvasGridForTest != null }
        // Montada não basta: a janela do IME precisa estar VISÍVEL, senão o toque cai em janela alheia
        // e a plataforma recusa a injeção (ver [inject]).
        waitUntil("janela do teclado não ficou visível") {
            KeyFlowKeyboardService.activeViewForTest?.canvasGridForTest?.isShown == true
        }
    }

    private fun tapKey(grid: KeyboardGridView, label: String) {
        waitUntil("grade Canvas ainda sem geometria medida") { grid.placedRowsForTest.isNotEmpty() }
        val placed = grid.placedRowsForTest.flatMap { it.keys }
            .first { (it.keyDef.action as? CanvasKeyAction.Char)?.text == label }
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        val x = (loc[0] + (placed.left + placed.right) / 2).toInt()
        val y = (loc[1] + (placed.top + placed.bottom) / 2).toInt()
        tapScreen(x, y)
    }

    private fun tapAction(grid: KeyboardGridView, action: CanvasKeyAction) {
        val placed = grid.placedRowsForTest.flatMap { it.keys }.first { it.keyDef.action == action }
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        tapScreen((loc[0] + (placed.left + placed.right) / 2).toInt(), (loc[1] + (placed.top + placed.bottom) / 2).toInt())
    }

    /** O corretor e os acentos são do português: fixa pt durante [block] e devolve o idioma do aparelho. */
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

    private fun tapBackspaceOnGrid(grid: KeyboardGridView) {
        val placed = grid.placedRowsForTest.flatMap { it.keys }
            .first { it.keyDef.action == CanvasKeyAction.Backspace }
        val loc = IntArray(2).also { grid.getLocationOnScreen(it) }
        val x = (loc[0] + (placed.left + placed.right) / 2).toInt()
        val y = (loc[1] + (placed.top + placed.bottom) / 2).toInt()
        tapScreen(x, y)
    }

    /**
     * Injeta um toque, devolvendo `false` se a plataforma RECUSAR a injeção:
     *
     * ```
     * IllegalArgumentException: Targeted input event injection from pid N was not directed at a
     * window owned by uid M
     * ```
     *
     * `sendPointerSync` só entrega em janela do próprio app, e logo depois de reinstalar o APK a janela
     * do IME ainda não assumiu aquela região da tela — o toque cai em janela alheia e a injeção é
     * recusada. Medido em 2026-09-08: as DUAS primeiras rodadas após um `installDebug` falhavam assim,
     * e as seguintes passavam. A exceção escapava do laço de [performUntil] e reprovava o teste na hora,
     * em vez de contar como tentativa — que é justamente o que aquele laço existe para absorver.
     */
    private fun tapScreen(x: Int, y: Int): Boolean {
        val time = SystemClock.uptimeMillis()
        return inject(MotionEvent.ACTION_DOWN, x, y, time) &&
            inject(MotionEvent.ACTION_UP, x, y, SystemClock.uptimeMillis())
    }

    private fun inject(action: Int, x: Int, y: Int, time: Long): Boolean {
        val event = MotionEvent.obtain(time, time, action, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try {
            instrumentation.sendPointerSync(event)
            true
        } catch (e: IllegalArgumentException) {
            false // janela alvo ainda não é do IME — quem chama repete
        } finally {
            event.recycle()
        }
    }

    private fun waitUntil(message: String, timeoutMs: Long = TIMEOUT, condition: () -> Boolean) {
        if (!waitUntilOrFalse(timeoutMs, condition)) throw AssertionError(message)
    }

    private fun waitUntilOrFalse(timeoutMs: Long = TIMEOUT, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    /**
     * Lê a FONTE DE VERDADE (o `EditText` do probe), não a árvore de acessibilidade. Medido em
     * 2026-09-08: no instante em que este teste lia "Toque para ditar…" (o hint), o log do IME mostrava
     * `setComposingText('o') -> true` — o texto já estava no campo e o snapshot do uiautomator é que
     * estava velho. Era isso, e não "toque perdido", a causa da intermitência de ~1 em 19.
     */
    private fun fieldText(): String? = ImeProbeActivity.liveText

    /**
     * Repete [action] até o campo mostrar [expectedText]. A explicação antiga aqui — "a entrega do
     * toque via `sendPointerSync` é intermitente" — foi MEDIDA e é falsa: o toque chega, resolve a
     * tecla certa e o `setComposingText` devolve true (2026-09-08). A intermitência era da leitura,
     * hoje corrigida em [fieldText]. As tentativas ficam como rede contra carga extrema do host.
     */
    private fun performUntil(expectedText: String, attempts: Int = 3, action: () -> Unit) {
        repeat(attempts) {
            action()
            if (waitUntilOrFalse(1500) { fieldText() == expectedText }) return
        }
        throw AssertionError("campo não chegou a \"$expectedText\" após $attempts tentativas; atual: ${fieldText()}")
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
