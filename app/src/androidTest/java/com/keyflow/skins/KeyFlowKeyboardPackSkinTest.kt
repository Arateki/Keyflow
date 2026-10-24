package com.keyflow.skins

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.ime.KeyFlowKeyboardService
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.ime.KeyboardColors
import com.keyflow.ime.KeyboardSkin
import com.keyflow.ime.UserSettingsGuard
import com.keyflow.ime.awaitKeyboardAttached
import com.keyflow.ime.keyboard.layout.KeyAction
import com.keyflow.ime.keyboard.layout.PlacedKey
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SKP-T4, no IME de verdade: com um pacote instalado por código e `keyboardSkin = "pack:<id>"`, o `KeyFlowKeyboardService` real desenha as
 * faces dos SVGs (pixel conferido), a bandeja, a tinta `ink`; trocar a skin ao vivo reconstrói; pacote removido, sumido ou quebrado cai no
 * Sistema sem crash; reimportar o pacote selecionado mostra a versão nova sem mexer nos ajustes.
 *
 * Roda contra o DataStore e a pasta `skins/` reais do app (é a única forma de observar o que o serviço persistiu e leu): guarda e devolve os
 * ajustes de quem é dono do aparelho e remove os pacotes que criou. **Só no emulador.**
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardPackSkinTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val repo get() = SettingsRepository(context)
    private val store get() = SkinPackStore.of(context)
    private val userSettings = UserSettingsGuard(context)

    private lateinit var savedSettings: Settings
    private val installedIds = mutableListOf<String>()

    @Before
    fun setUp() {
        userSettings.capture()
        userSettings.forceExpanded()
        savedSettings = runBlocking { repo.settingsFlow().first() }
    }

    @After
    fun tearDown() {
        runBlocking { repo.saveSettings(savedSettings) }
        installedIds.forEach { store.remove(it) }
        userSettings.restore()
        device.pressHome()
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    // ── pacote de teste ──────────────────────────────────────────────────────

    /** Faces em cores puras e fáceis de achar: letra vermelha, função azul, bandeja verde, tinta branca, balão amarelo. */
    private fun installPack(name: String, key: String = "FF0000", modifier: String = "0000FF", tray: String? = "00AA00"): String {
        val entries = buildList {
            add("manifest.json" to SkinPackFixtures.manifest(name, ""","keyboard":{"ink":"#FFFFFF","popup":"#FFFF00","trayColor":"#333333","navigationBarColor":"#333333"}"""))
            add("keyboard_key_normal.svg" to SkinPackFixtures.svg(key))
            add("keyboard_modifier_normal.svg" to SkinPackFixtures.svg(modifier))
            tray?.let { add("keyboard_tray.svg" to SkinPackFixtures.svg(it)) }
        }
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        assertTrue(result.report.errors.toString(), result.installed)
        installedIds += result.packId!!
        return result.skinId!!
    }

    private fun selectSkin(skinId: String) {
        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(keyboardSkin = skinId)) }
    }

    // ── o IME real ───────────────────────────────────────────────────────────

    private fun launchKeyboard(): KeyFlowKeyboardView {
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
        return measuredView(previous = null)
    }

    /** Espera uma `KeyFlowKeyboardView` (diferente de [previous]) com a grade medida. */
    private fun measuredView(previous: KeyFlowKeyboardView?): KeyFlowKeyboardView {
        waitUntil("o teclado não foi (re)criado") {
            val view = KeyFlowKeyboardService.activeViewForTest
            view != null && view !== previous && view.canvasGridForTest?.placedRowsForTest?.isNotEmpty() == true && view.width > 0
        }
        return KeyFlowKeyboardService.activeViewForTest!!
    }

    /** O teclado inteiro (bandeja, barras e grade) desenhado num bitmap, na thread principal. */
    private fun snapshot(view: KeyFlowKeyboardView): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun originOf(view: View, root: View): Pair<Int, Int> {
        val a = IntArray(2)
        val b = IntArray(2)
        view.getLocationInWindow(a)
        root.getLocationInWindow(b)
        return (a[0] - b[0]) to (a[1] - b[1])
    }

    private fun findKey(view: KeyFlowKeyboardView, predicate: (KeyAction) -> Boolean): PlacedKey =
        view.canvasGridForTest!!.placedRowsForTest.flatMap { it.keys }.first { predicate(it.keyDef.action) }

    /** O pixel logo dentro do canto de cima à esquerda da face de uma tecla da grade (longe do rótulo, que é no centro). */
    private fun keyFacePixel(view: KeyFlowKeyboardView, bitmap: Bitmap, key: PlacedKey): Int {
        val grid = view.canvasGridForTest!!
        val (gx, gy) = originOf(grid, view)
        val density = context.resources.displayMetrics.density
        val margin = 3 * density // `keyMargin` da grade
        return bitmap.getPixel((gx + key.left + margin + 5 * density).toInt(), (gy + key.top + margin + 5 * density).toInt())
    }

    private fun assertColor(actual: Int, expected: Int, what: String, tolerance: Int = 4) {
        val ok = abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
            abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
            abs(Color.blue(actual) - Color.blue(expected)) <= tolerance
        assertTrue("$what: esperava #${"%06X".format(expected and 0xFFFFFF)}, veio #${"%06X".format(actual and 0xFFFFFF)}", ok)
    }

    private val systemKey: Int get() = KeyboardColors.of(KeyboardSkin.SYSTEM, runBlocking { repo.themeDarkOnce() }).key

    /**
     * Espera a face de uma tecla de letra do teclado ATIVO chegar a [expected]. A reconstrução ao mudar a skin é assíncrona (o serviço reage ao fluxo
     * dos ajustes) e a amostra única logo depois de `measuredView` pegava às vezes uma vista ainda sem o desenho final (`#D1D1D1`, a cor das teclas de
     * função, no lugar de `#FDFDFD`): medido, esse teste falhava em 6 de 12 rodadas isoladas no commit da fase 1 (0f0edab) — não é regressão da T6.
     */
    private fun assertLetterFaceEventually(expected: Int, what: String, timeoutMs: Long = 8_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = 0
        while (System.currentTimeMillis() < deadline) {
            val view = KeyFlowKeyboardService.activeViewForTest
            if (view != null && view.width > 0 && view.canvasGridForTest?.placedRowsForTest?.isNotEmpty() == true) {
                last = keyFacePixel(view, snapshot(view), findKey(view) { it is KeyAction.Char })
                if (abs(Color.red(last) - Color.red(expected)) <= 4 && abs(Color.green(last) - Color.green(expected)) <= 4 && abs(Color.blue(last) - Color.blue(expected)) <= 4) return
            }
            Thread.sleep(200)
        }
        assertColor(last, expected, what)
    }

    // ── testes ───────────────────────────────────────────────────────────────

    @Test
    fun thePackFacesTrayAndInkAreDrawnByTheRealKeyboard() {
        selectSkin(installPack("Cores IME"))
        val view = launchKeyboard()
        val bitmap = snapshot(view)

        val letter = findKey(view) { it is KeyAction.Char }
        assertColor(keyFacePixel(view, bitmap, letter), 0xFFFF0000.toInt(), "face da letra")
        val backspace = findKey(view) { it == KeyAction.Backspace }
        assertColor(keyFacePixel(view, bitmap, backspace), 0xFF0000FF.toInt(), "face do apagar (função)")
        val space = findKey(view) { it == KeyAction.Space }
        assertColor(keyFacePixel(view, bitmap, space), 0xFF0000FF.toInt(), "face do espaço (função, FORMAT.md §2)")

        // A bandeja: o vão entre a borda da grade e a primeira tecla (a margem de 3 dp) mostra o fundo do teclado.
        val grid = view.canvasGridForTest!!
        val (gx, gy) = originOf(grid, view)
        assertColor(bitmap.getPixel(gx + 1, gy + grid.height / 2), 0xFF00AA00.toInt(), "bandeja (keyboard_tray.svg)")

        // A tinta: o rótulo da letra é branco (`keyboard.ink`) sobre a face vermelha — algum pixel do centro da tecla é quase branco.
        val (kx, ky) = gx + ((letter.left + letter.right) / 2).toInt() to gy + ((letter.top + letter.bottom) / 2).toInt()
        var whiteish = 0
        for (y in ky - 12..ky + 12) for (x in kx - 12..kx + 12) {
            val p = bitmap.getPixel(x, y)
            if (Color.red(p) > 235 && Color.green(p) > 235 && Color.blue(p) > 235) whiteish++
        }
        assertTrue("o rótulo deveria sair na tinta do manifest (branca): $whiteish px", whiteish > 10)

        // As barras também são de pacote: o botão de config é função (azul).
        val bar = view.barViewsForTest.first()
        val (bx, by) = originOf(bar, view)
        // Nem o mic (tingido de verde com um campo em foco) nem a faixa de sugestões: um botão comum, de função.
        val button = bar.layoutForTest.buttons.first {
            it.button != com.keyflow.core.shortcuts.BarButton.Mic && it.button != com.keyflow.core.shortcuts.BarButton.Candidates
        }
        val density = context.resources.displayMetrics.density
        val sample = bitmap.getPixel((bx + button.left + 3 * density + 5 * density).toInt(), (by + 2 * density + 5 * density).toInt())
        assertColor(sample, 0xFF0000FF.toInt(), "botão da barra (função)")
    }

    @Test
    fun switchingTheSkinLiveRebuildsTheKeyboardAndRemovingThePackFallsBackToSystem() {
        val packSkin = installPack("Ao vivo")
        selectSkin("system")
        var view = launchKeyboard()
        val letter = findKey(view) { it is KeyAction.Char }
        assertColor(keyFacePixel(view, snapshot(view), letter), systemKey, "começa no Sistema")

        selectSkin(packSkin)
        view = measuredView(previous = view)
        assertColor(keyFacePixel(view, snapshot(view), findKey(view) { it is KeyAction.Char }), 0xFFFF0000.toInt(), "trocou para o pacote ao vivo")

        selectSkin("system")
        view = measuredView(previous = view)
        assertColor(keyFacePixel(view, snapshot(view), findKey(view) { it is KeyAction.Char }), systemKey, "voltou ao Sistema ao vivo")

        selectSkin(packSkin)
        view = measuredView(previous = view)
        // Remover o pacote selecionado: os ajustes ainda dizem pack:<id>, e o teclado reabre no Sistema (SKP-STO-03/04).
        assertTrue(store.remove(SkinIds.packId(packSkin)!!))
        view = measuredView(previous = view)
        assertColor(keyFacePixel(view, snapshot(view), findKey(view) { it is KeyAction.Char }), systemKey, "pacote removido → Sistema")
        assertEquals(packSkin, runBlocking { repo.keyboardSkinOnce() })
    }

    @Test
    fun aMissingOrBrokenPackFallsBackToSystemWithoutCrashing() {
        selectSkin(SkinIds.forPack("nao-existe-00000000"))
        var view = launchKeyboard()
        assertLetterFaceEventually(systemKey, "pacote inexistente → Sistema")

        // Um pacote instalado cuja tecla normal foi corrompida em disco depois.
        val packSkin = installPack("Quebrado")
        java.io.File(java.io.File(context.filesDir, "skins/${SkinIds.packId(packSkin)}"), "keyboard_key_normal.svg").writeText("<svg><rect></svg>")
        selectSkin(packSkin)
        view = measuredView(previous = view)
        assertLetterFaceEventually(systemKey, "tecla normal corrompida → Sistema")

        // Um id que nem é de pacote válido (caminho): o teclado ignora e usa o Sistema.
        selectSkin("pack:../../x")
        view = measuredView(previous = view)
        assertLetterFaceEventually(systemKey, "id inseguro → Sistema")
    }

    @Test
    fun reimportingTheSelectedPackShowsTheNewVersionLiveWithoutTouchingTheSettings() {
        val packSkin = installPack("Reimporta", key = "FF0000")
        selectSkin(packSkin)
        var view = launchKeyboard()
        assertColor(keyFacePixel(view, snapshot(view), findKey(view) { it is KeyAction.Char }), 0xFFFF0000.toInt(), "versão 1")

        val again = installPack("Reimporta", key = "00FF00")
        assertEquals("o mesmo id (SKP-IMP-05)", packSkin, again)
        view = measuredView(previous = view)
        assertColor(keyFacePixel(view, snapshot(view), findKey(view) { it is KeyAction.Char }), 0xFF00FF00.toInt(), "versão 2, ao vivo")
        assertEquals("os ajustes não mudaram", packSkin, runBlocking { repo.keyboardSkinOnce() })
    }

    @Test
    fun openingThePackInTheCriticalPathIsFast() {
        val packSkin = installPack("Medida")
        val pack = store.loadBySkinId(packSkin)!!
        val micros = ArrayList<Long>()
        repeat(15) {
            val t0 = System.nanoTime()
            val keyboard = store.loadBySkinId(packSkin)?.let { PackKeyboard.load(it) }
            micros += (System.nanoTime() - t0) / 1000
            assertNotNull(keyboard)
        }
        micros.sort()
        android.util.Log.i("SKP", "SKP-T4 abrir pack (manifest + 3 SVGs de teclado) no emulador: mediana=${micros[7]}µs p95=${micros[13]}µs primeira=${micros.minOrNull()}µs id=${pack.id}")
        assertTrue("abrir o pacote não pode custar mais que alguns ms: ${micros[7]}µs", micros[7] < 50_000)
    }

    private fun waitUntil(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
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
        const val IME = "com.arateki.keyflow/com.keyflow.ime.KeyFlowKeyboardService"
        const val TIMEOUT = 5000L
    }
}
