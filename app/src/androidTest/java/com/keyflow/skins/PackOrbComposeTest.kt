package com.keyflow.skins

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.skins.SkinPackFixtures.manifest
import com.keyflow.skins.SkinPackFixtures.svg
import com.keyflow.ui.orb.LocalOrbPack
import com.keyflow.ui.orb.LocalOrbStyle
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.VoiceOrb
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SKP-T5, no `VoiceOrb` de verdade (Compose): com o relógio congelado a orbe de pacote desenha o frame do estado (pixel conferido) e o
 * tempo/glifos do centro continuam sendo do app, na `orb.centerInk`; sem pacote o estilo PACK cai no Ateliê.
 */
@RunWith(AndroidJUnit4::class)
class PackOrbComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var base: File
    private lateinit var store: SkinPackStore
    private val opened = mutableListOf<PackOrb>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base = SkinPackFixtures.tempRoot(context.cacheDir)
        store = SkinPackStore(File(base, "skins"))
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        base.deleteRecursively()
    }

    /** Orbe com idle vermelha (2 frames) e listening azul; tinta do centro verde pura. */
    private fun pack(): PackOrb {
        val entries = listOf(
            "manifest.json" to manifest("Compose", ""","orb":{"fps":10,"centerInk":"#00FF00"}"""),
            "orb_idle_01.svg" to svg("FF0000"),
            "orb_idle_02.svg" to svg("FFFF00"),
            "orb_listening_01.svg" to svg("0000FF"),
        )
        val result = store.import(SkinPackFixtures.zip(entries).inputStream())
        assertTrue(result.report.errors.toString(), result.installed)
        return PackOrb.open(store.load(result.packId!!)!!)!!.also { opened += it }
    }

    private fun show(state: OrbState, center: OrbCenter, pack: PackOrb?, timeMs: Float = 0f, style: OrbStyle = OrbStyle.PACK) {
        rule.setContent {
            CompositionLocalProvider(LocalOrbPack provides pack, LocalOrbStyle provides style) {
                Box(Modifier.size(120.dp).testTag(TAG)) {
                    VoiceOrb(state, Modifier.size(120.dp), center = center, timeProvider = { timeMs }, style = style)
                }
            }
        }
    }

    /**
     * `captureToImage` espera um redesenho da janela por no máximo 2 s — o limite é fixo na biblioteca — e um emulador de software com o host
     * carregado às vezes demora mais que isso (`ComposeTimeoutException`, medido: 2 de 4 rodadas do pacote sob carga). No swangle a mesma
     * espera estoura como `AssertionError("Failed waiting for PixelCopy!")` (CI, 2026-10-10). Só essas duas são repetidas, até 5 vezes:
     * o que a captura devolve continua sendo conferido pixel a pixel pelos testes.
     */
    private fun capture(): android.graphics.Bitmap {
        var timedOut: Throwable? = null
        repeat(5) {
            try {
                return rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
            } catch (e: ComposeTimeoutException) {
                timedOut = e
            } catch (e: AssertionError) {
                if (e.message?.contains("PixelCopy") != true) throw e
                timedOut = e
            }
        }
        throw timedOut!!
    }

    private fun near(actual: Int, expected: Int) =
        abs(Color.red(actual) - Color.red(expected)) <= 3 && abs(Color.green(actual) - Color.green(expected)) <= 3 && abs(Color.blue(actual) - Color.blue(expected)) <= 3

    @Test
    fun theOrbDrawsTheFrameOfTheCurrentStateAtTheFrozenTime() {
        show(OrbState.Idle, OrbCenter.None, pack(), timeMs = 0f)
        var bitmap = capture()
        assertTrue("idle t=0: frame vermelho", near(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2), Color.RED))
        assertTrue("o frame ocupa o quadrado inteiro: o canto também", near(bitmap.getPixel(4, 4), Color.RED))
    }

    @Test
    fun theFrozenClockPicksTheFrameByTime() {
        show(OrbState.Idle, OrbCenter.None, pack(), timeMs = 100f) // 10 fps → frame 2
        val bitmap = capture()
        assertTrue("idle t=100ms: frame 2 (amarelo)", near(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2), Color.YELLOW))
    }

    @Test
    fun eachStateDrawsItsOwnFramesAndAStateWithoutFramesUsesIdle() {
        show(OrbState.Listening, OrbCenter.None, pack(), timeMs = 0f)
        var bitmap = capture()
        assertTrue("listening: azul", near(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2), Color.BLUE))
    }

    @Test
    fun pausedWithoutFramesFallsBackToIdle() {
        show(OrbState.Paused, OrbCenter.None, pack(), timeMs = 0f)
        val bitmap = capture()
        assertTrue("paused sem frames: idle (vermelho)", near(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2), Color.RED))
    }

    @Test
    fun theTimeAndTheGlyphsStayTheAppsAndUseTheManifestCenterInk() {
        show(OrbState.Listening, OrbCenter.Time(12), pack(), timeMs = 0f)
        val bitmap = capture()
        // O fundo é azul puro; o `0:12` é desenhado pelo app por cima, na centerInk (verde puro): algum pixel do miolo é verde.
        var green = 0
        for (y in bitmap.height / 3..bitmap.height * 2 / 3) for (x in bitmap.width / 4..bitmap.width * 3 / 4) {
            val p = bitmap.getPixel(x, y)
            if (Color.green(p) > 200 && Color.red(p) < 80 && Color.blue(p) < 80) green++
        }
        assertTrue("o tempo deveria sair na centerInk do pacote ($green px verdes)", green > 20)
    }

    @Test
    fun withoutAPackThePackStyleFallsBackToTheAtelieOrb() {
        show(OrbState.Idle, OrbCenter.None, pack = null, timeMs = 0f)
        val bitmap = capture()
        val p = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue("sem pacote desenha o Ateliê (um blob verde-água), não nada", Color.alpha(p) > 0)
        assertTrue("e não é a cor de um pacote", !near(p, Color.RED) && !near(p, Color.BLUE))
        assertEquals("o Ateliê é opaco no miolo", 255, Color.alpha(p))
    }

    private companion object {
        const val TAG = "pack-orb"
    }
}
