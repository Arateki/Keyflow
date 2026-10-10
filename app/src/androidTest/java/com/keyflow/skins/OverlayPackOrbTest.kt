package com.keyflow.skins

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.overlay.OverlayService
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SKP-T5, na orbe flutuante de VERDADE (`OverlayService` + `OrbButtonView` + Compose na janela do sistema): com `orbSkin = "pack:<id>"` a orbe
 * sai com o frame do pacote (cor sólida inconfundível no screenshot); trocar de skin, reimportar o pacote selecionado e removê-lo mudam a orbe ao
 * vivo — e um pacote que sumiu devolve o Ateliê, sem derrubar o serviço.
 *
 * Roda contra o DataStore e a pasta `skins/` reais do app: guarda e devolve os ajustes de quem é dono do aparelho. **Só no emulador.**
 */
@RunWith(AndroidJUnit4::class)
class OverlayPackOrbTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val repo get() = SettingsRepository(context)
    private val store get() = SkinPackStore.of(context)

    private lateinit var saved: Settings
    private val installed = mutableListOf<String>()

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        saved = runBlocking { repo.settingsFlow().first() }
        runBlocking { repo.saveSettings(saved.copy(orbSkin = "atelie", orbSizePx = 195, overlayModeEnabled = true)) }
        context.startForegroundService(OverlayService.startOverlayIntent(context))
        waitUntil("o serviço do overlay não subiu") { OverlayService.isRunningForUi }
    }

    @After
    fun tearDown() {
        context.stopService(OverlayService.intent(context))
        runBlocking { repo.saveSettings(saved) }
        installed.forEach { store.remove(it) }
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    /** Um pacote só de orbe cujo frame de idle é um quadrado inteiro de [color] (hex sem `#`). */
    private fun installPack(name: String, color: String): String {
        val entries = listOf(
            "manifest.json" to SkinPackFixtures.manifest(name),
            "orb_idle_01.svg" to SkinPackFixtures.svg(color),
        )
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        assertTrue(result.report.errors.toString(), result.installed)
        installed += result.packId!!
        return result.skinId!!
    }

    private fun selectOrb(skinId: String) {
        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(orbSkin = skinId)) }
    }

    /** `null` quando a captura do sistema falha — no emulador do CI isso acontece de vez em quando (2026-10-10); quem espera tenta de novo. */
    private fun screenshot(): Bitmap? = instrumentation.uiAutomation.takeScreenshot()

    /** Quantos pixels do screenshot estão a menos de [tolerance] por canal de [rgb]. */
    private fun countNear(bitmap: Bitmap, rgb: Int, tolerance: Int = 12): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var n = 0
        for (p in pixels) {
            if (abs(Color.red(p) - Color.red(rgb)) <= tolerance && abs(Color.green(p) - Color.green(rgb)) <= tolerance && abs(Color.blue(p) - Color.blue(rgb)) <= tolerance) n++
        }
        return n
    }

    private val magenta = 0xFFFF00FF.toInt()
    private val cyan = 0xFF00FFFF.toInt()
    private val atelie = 0xFF4E9683.toInt() // MainOuter da orbe Ateliê

    /**
     * Espera o screenshot ter (ou não) pelo menos [min] px de [rgb] — a orbe leva uns quadros para refletir o ajuste, e a PRIMEIRA vez, com o
     * processo recém-aberto (Compose frio, renderização por software), leva muito mais: medido sob carga no host, 7–13 s até o Ateliê aparecer
     * depois de o serviço subir (e 4–5 s no resto). É uma espera por condição, então o limite longo não custa nada quando tudo está rápido.
     */
    private fun waitForColor(rgb: Int, present: Boolean, what: String, min: Int = 6000, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            val shot = screenshot()
            if (shot != null) {
                last = countNear(shot, rgb)
                if ((last >= min) == present && (present || last < 300)) return
            }
            Thread.sleep(250)
        }
        throw AssertionError("$what: esperava ${if (present) "ter" else "não ter"} #${"%06X".format(rgb and 0xFFFFFF)} na tela; último count=$last")
    }

    @Test
    fun theFloatingOrbDrawsThePackFrameAndFollowsLiveChanges() {
        waitForColor(atelie, present = true, what = "começa com a orbe Ateliê", min = 300)

        val packSkin = installPack("Orbe ao vivo", "FF00FF")
        selectOrb(packSkin)
        waitForColor(magenta, present = true, what = "orbe do pacote (frame magenta)")
        waitForColor(atelie, present = false, what = "o Ateliê saiu de cena")

        // Reimportar o pacote selecionado (mesmo nome → mesmo id): a versão nova aparece SEM mexer nos ajustes (SKP-IMP-05).
        assertTrue(installPack("Orbe ao vivo", "00FFFF") == packSkin)
        waitForColor(cyan, present = true, what = "versão nova do pacote (ciano), ao vivo")
        waitForColor(magenta, present = false, what = "a versão antiga saiu")

        // Voltar ao Ateliê pelos ajustes.
        selectOrb("atelie")
        waitForColor(atelie, present = true, what = "de volta ao Ateliê", min = 300)
        waitForColor(cyan, present = false, what = "o pacote saiu de cena")

        // De novo o pacote, e agora ele é REMOVIDO com a skin ainda selecionada: a orbe cai no Ateliê, sem derrubar o serviço (SKP-STO-03/04).
        selectOrb(packSkin)
        waitForColor(cyan, present = true, what = "pacote selecionado outra vez")
        assertTrue(store.remove(SkinIds.packId(packSkin)!!))
        waitForColor(cyan, present = false, what = "o pacote removido sumiu")
        waitForColor(atelie, present = true, what = "pacote removido → Ateliê", min = 300)
        assertTrue("o serviço do overlay continua de pé", OverlayService.isRunningForUi)
    }

    @Test
    fun aMissingPackFallsBackToTheAtelieOrbWithoutCrashing() {
        selectOrb(SkinIds.forPack("nao-existe-00000000"))
        waitForColor(atelie, present = true, what = "pacote inexistente → Ateliê", min = 300)
        selectOrb("pack:../../x")
        waitForColor(atelie, present = true, what = "id inseguro → Ateliê", min = 300)
        assertTrue(OverlayService.isRunningForUi)
    }

    private fun waitUntil(message: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
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
}
