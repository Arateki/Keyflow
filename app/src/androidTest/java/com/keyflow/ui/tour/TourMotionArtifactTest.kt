package com.keyflow.ui.tour

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.Settings
import com.keyflow.ui.theme.KeyFlowTheme
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.tour.scenes.KeyboardScene
import com.keyflow.ui.tour.scenes.OrbScene
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Artefato de movimento do tour, no mesmo espírito dos GIFs do Q6/Q7: varre o relógio de um roteiro e
 * salva um frame por instante em `externalFilesDir/motion-gifs/tour-*`, para revisão humana.
 *
 * Não é golden e não afirma nada — existe porque **fluidez não se julga em frame parado**, e o emulador
 * daqui roda headless. O gate de fluidez continua sendo o manual no A32 (GT-T8); isto só encurta o
 * ciclo enquanto o tour está sendo construído.
 */
@RunWith(AndroidJUnit4::class)
class TourMotionArtifactTest {

    @get:Rule
    val rule = createComposeRule()

    // Só gera os quadros dos GIFs, sem nada a conferir: com `-e motionFrames false` (o gate do CI) é pulado. No emulador
    // do CI o captureToImage destes quadros ainda estourava o prazo do PixelCopy (2026-10-10); os scripts verify-*-gifs.sh
    // não passam o argumento e seguem gravando.
    @Before
    fun onlyWhenRecordingFrames() {
        assumeTrue(
            "motionFrames=false: sem quadros para gravar",
            InstrumentationRegistry.getArguments().getString("motionFrames") != "false",
        )
    }

    private val outRoot: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "motion-gifs").apply { mkdirs() }
    }

    private var now by mutableFloatStateOf(0f)

    @Test
    fun captureOrbTourFrames() {
        val scripts = buildOrbScripts(Settings())

        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalTourClock provides { now }) {
                    val palette = LocalKeyFlowPalette.current
                    GestureTourPlayer(
                        scripts = scripts,
                        modifier = Modifier
                            .size(240.dp)
                            .background(palette.bg)
                            .testTag(SCENE),
                    ) { frame -> OrbScene(frame, Modifier.size(240.dp)) }
                }
            }
        }

        // Os três primeiros: gravar/parar, pausar/retomar e cancelar/desfazer — este último tem
        // arrasto, alvo e banner, o mais difícil de acertar sem ver a sequência.
        capture(scripts.take(3), "tour-orb")
    }

    @Test
    fun captureKeyboardTourFrames() {
        val scripts = buildKeyboardScripts(Settings())

        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalTourClock provides { now }) {
                    val palette = LocalKeyFlowPalette.current
                    GestureTourPlayer(
                        scripts = scripts,
                        modifier = Modifier
                            .width(300.dp)
                            .background(palette.bg)
                            .testTag(SCENE),
                    ) { frame -> KeyboardScene(frame, Modifier.fillMaxWidth()) }
                }
            }
        }

        capture(scripts, "tour-keyboard")
    }

    /** Varre o relógio dos [scripts] e salva um PNG por instante em `motion-gifs/[slug]`. */
    private fun capture(scripts: List<GestureScript>, slug: String) {
        val total = scripts.sumOf { it.totalDurationMs }
        val dir = File(outRoot, slug).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }

        var index = 0
        var t = 0
        while (t <= total) {
            rule.runOnUiThread { now = t.toFloat() }
            rule.waitForIdle()
            val bitmap = rule.onNodeWithTag(SCENE).captureToImage().asAndroidBitmap()
            File(dir, "frame-%03d.png".format(index++)).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            t += STEP_MS
        }
        File(dir, "scenario.txt").writeText(
            "roteiros: " + scripts.joinToString { it.id } +
                " | passo do relógio: ${STEP_MS}ms | frames: " + index,
        )
    }

    private companion object {
        const val SCENE = "tour-motion-scene"
        const val STEP_MS = 120
    }
}
