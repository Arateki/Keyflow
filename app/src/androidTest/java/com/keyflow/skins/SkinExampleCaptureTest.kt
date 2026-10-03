package com.keyflow.skins

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
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
import com.keyflow.ime.UserSettingsGuard
import com.keyflow.ime.awaitKeyboardAttached
import com.keyflow.ime.keyboard.layout.KeyAction
import com.keyflow.ime.keyboard.layout.PlacedKey
import com.keyflow.overlay.OrbButtonView
import com.keyflow.overlay.OverlayService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * SKP-T8, evidência visual (só com `-e skpShots 1`; fora disso é "assumption"): instala o pacote de exemplo REAL (dos assets do APK de teste) no
 * `filesDir/skins` do app, escolhe-o nos ajustes para o teclado e a orbe e salva screenshots da tela inteira em
 * `/sdcard/Android/data/com.keyflow/files/skp-shots/` (puxar com `adb pull`):
 *
 *  - **o IME real** (`KeyFlowKeyboardService` num campo do `ImeProbeActivity`): repouso, com uma tecla de letra e uma de função **seguradas**
 *    (a face pressionada) e, sem pacote, o Sistema, para comparar;
 *  - **a orbe flutuante real** (`OverlayService`, a janela do sistema com a `OrbButtonView`) nos quatro estados — o estado é posto por
 *    `OrbButtonView.render(state)`, o mesmo que o coordenador do serviço chama; o gesto/microfone de verdade não é exercitado — em 156 px,
 *    195 px (o padrão) e 280 px (o máximo do slider), cada um com uma sequência de quadros para montar GIF.
 *
 * Devolve os ajustes e remove o pacote no fim. **Só no emulador.**
 */
class SkinExampleCaptureTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val repo get() = SettingsRepository(context)
    private val store get() = SkinPackStore.of(context)
    private val userSettings = UserSettingsGuard(context)
    private val out: File by lazy { File(context.getExternalFilesDir(null), "skp-shots").apply { mkdirs() } }

    private lateinit var saved: Settings
    private var packId: String? = null
    private var overlayStarted = false

    @Before
    fun setUp() {
        assumeTrue("só com -e skpShots 1", InstrumentationRegistry.getArguments().getString("skpShots") != null)
        userSettings.capture()
        userSettings.forceExpanded()
        saved = runBlocking { repo.settingsFlow().first() }
    }

    @After
    fun tearDown() {
        if (!::saved.isInitialized) return
        if (overlayStarted) context.stopService(OverlayService.intent(context))
        runBlocking { repo.saveSettings(saved) }
        packId?.let { store.remove(it) }
        userSettings.restore()
        device.pressHome()
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { pfd -> FileInputStream(pfd.fileDescriptor).use { it.readBytes().decodeToString() } }

    /** O `.zip` do exemplo montado dos assets (o que `scripts/build-skin-pack.sh` faz com a pasta). */
    private fun exampleZip(): ByteArray {
        val assets = instrumentation.context.assets
        val names = assets.list("exemplo-neon")!!.filter { it == "manifest.json" || it.endsWith(".svg") }.sorted()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(assets.open("exemplo-neon/$name").use { it.readBytes() })
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun installExample(): String {
        val result = store.import(exampleZip().inputStream())
        assertTrue(result.report.errors.toString(), result.installed)
        assertTrue("o exemplo importa sem avisos: ${result.report.warnings}", result.report.warnings.isEmpty())
        packId = result.packId
        return result.skinId!!
    }

    private fun save(shot: Bitmap, name: String) {
        File(out, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screenshot(): Bitmap = instrumentation.uiAutomation.takeScreenshot()

    private fun waitUntil(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError(message)
    }

    // ── o IME real ───────────────────────────────────────────────────────────

    private fun launchKeyboard() {
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("ime enable $IME")
        shell("ime set $IME")
        val intent = Intent().setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.desc("ime_probe_field")), 5000)
        device.findObject(By.desc("ime_probe_field"))?.click()
        awaitKeyboardAttached(device) { context.startActivity(intent) }
        waitUntil("o teclado não mediu a grade") {
            val view = KeyFlowKeyboardService.activeViewForTest
            view != null && view.canvasGridForTest?.placedRowsForTest?.isNotEmpty() == true && view.width > 0
        }
        Thread.sleep(800)
    }

    private fun keyCenterOnScreen(predicate: (KeyAction) -> Boolean): Pair<Float, Float> {
        val view = KeyFlowKeyboardService.activeViewForTest!!
        val grid = view.canvasGridForTest!!
        val key: PlacedKey = grid.placedRowsForTest.flatMap { it.keys }.first { predicate(it.keyDef.action) }
        val at = IntArray(2)
        grid.getLocationOnScreen(at)
        return (at[0] + (key.left + key.right) / 2f) to (at[1] + (key.top + key.bottom) / 2f)
    }

    /** Segura o dedo em ([x],[y]) por [holdMs] e salva o screenshot com ele ainda embaixo (a face pressionada). */
    private fun shotWhileHolding(x: Float, y: Float, name: String, holdMs: Long = 500) {
        val down = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        instrumentation.uiAutomation.injectInputEvent(event, true)
        Thread.sleep(holdMs)
        save(screenshot(), name)
        val up = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        instrumentation.uiAutomation.injectInputEvent(up, true)
        event.recycle()
        up.recycle()
        Thread.sleep(300)
    }

    @Test
    fun keyboardShots() {
        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(keyboardSkin = "system")) }
        launchKeyboard()
        save(screenshot(), "ime-sistema")

        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(keyboardSkin = installExample())) }
        Thread.sleep(2500) // o serviço reconstrói a view ao vivo; os sprites esquentam no primeiro quadro
        val view = KeyFlowKeyboardService.activeViewForTest
        assertNotNull(view)
        save(screenshot(), "ime-neon-repouso")

        val (lx, ly) = keyCenterOnScreen { it is KeyAction.Char }
        shotWhileHolding(lx, ly, "ime-neon-letra-pressionada")
        val (sx, sy) = keyCenterOnScreen { it == KeyAction.Space }
        shotWhileHolding(sx, sy, "ime-neon-espaco-pressionado")
        val (bx, by) = keyCenterOnScreen { it == KeyAction.Backspace }
        shotWhileHolding(bx, by, "ime-neon-apagar-pressionado")
        Log.i("SKP", "SKP-SHOTS teclado salvo em $out")
    }

    // ── a orbe flutuante real ────────────────────────────────────────────────

    /** O `OrbButtonView` da janela flutuante do serviço, achado entre as janelas do processo (o serviço não o expõe). */
    private fun floatingOrb(): OrbButtonView {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
        return views.filterIsInstance<View>().firstNotNullOf { root -> findOrb(root) }
    }

    private fun findOrb(view: View): OrbButtonView? {
        if (view is OrbButtonView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findOrb(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun render(state: ButtonState) = instrumentation.runOnMainSync { floatingOrb().render(state) }

    private fun orbBounds(): android.graphics.Rect {
        val orb = floatingOrb()
        val at = IntArray(2)
        orb.getLocationOnScreen(at)
        return android.graphics.Rect(at[0], at[1], at[0] + orb.width, at[1] + orb.height)
    }

    /** Recorta o screenshot em volta da orbe (com folga para o halo) e salva. */
    private fun saveOrb(shot: Bitmap, name: String) {
        val b = orbBounds()
        val pad = (b.width() * 0.25f).toInt()
        val left = (b.left - pad).coerceAtLeast(0)
        val top = (b.top - pad).coerceAtLeast(0)
        val right = (b.right + pad).coerceAtMost(shot.width)
        val bottom = (b.bottom + pad).coerceAtMost(shot.height)
        save(Bitmap.createBitmap(shot, left, top, right - left, bottom - top), name)
    }

    @Test
    fun orbShots() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        val skinId = installExample()
        runBlocking { repo.saveSettings(saved.copy(orbSkin = skinId, orbSizePx = 195, overlayModeEnabled = true)) }
        context.startForegroundService(OverlayService.startOverlayIntent(context))
        overlayStarted = true
        waitUntil("o serviço do overlay não subiu") { OverlayService.isRunningForUi }
        waitUntil("a orbe flutuante não apareceu") { runCatching { floatingOrb() }.isSuccess }

        for (size in listOf(156, 195, 280)) {
            runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(orbSizePx = size)) }
            Thread.sleep(1500)
            for ((label, state, frames) in STATES) {
                render(state)
                Thread.sleep(1200) // o pré-aquecimento dos frames do estado, em segundo plano
                for (i in 0 until frames) {
                    saveOrb(screenshot(), "orbe-${size}px-$label-%02d".format(i))
                    Thread.sleep(FRAME_GAP_MS)
                }
            }
            render(ButtonState.IDLE_NO_FIELD)
        }
        // E o contraste: a mesma janela com o Ateliê.
        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(orbSkin = "atelie", orbSizePx = 195)) }
        Thread.sleep(1500)
        render(ButtonState.IDLE_NO_FIELD)
        Thread.sleep(500)
        saveOrb(screenshot(), "orbe-atelie-195px-idle-00")
        Log.i("SKP", "SKP-SHOTS orbe salva em $out")
    }

    private companion object {
        const val IME = "com.keyflow/.ime.KeyFlowKeyboardService"
        const val FRAME_GAP_MS = 90L

        /** (rótulo, estado do botão, quantos quadros capturar para o GIF) */
        val STATES = listOf(
            Triple("idle", ButtonState.IDLE_NO_FIELD, 12),
            Triple("listening", ButtonState.RECORDING, 16),
            Triple("paused", ButtonState.PAUSED, 2),
            Triple("transcribing", ButtonState.SENDING, 12),
        )
    }
}
