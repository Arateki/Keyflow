package com.keyflow.ui.orb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Artefato de movimento do mascote, no espírito dos GIFs do Q6/Q7/tour: varre o relógio da orbe num trecho de cada estado e salva
 * um quadro por instante em `externalFilesDir/mascot-gifs/<estado>`, lado a lado sobre branco e sobre preto, para o Yan ver o
 * movimento sem abrir o app (`scripts/verify-mascot-gifs.sh` monta os GIFs).
 *
 * Não é golden e não afirma nada além de existir um quadro por instante — **movimento não se julga num quadro parado**. Os trechos
 * foram escolhidos pelo calendário ([MascotTimeline]) para terem uma piscada, uma tremida de orelha e, quando o estado olha de
 * lado, um olhar. O tempo vem de um relógio injetado, então o mesmo trecho dá sempre os mesmos quadros.
 */
@RunWith(AndroidJUnit4::class)
class MascotMotionArtifactTest {

    @get:Rule
    val rule = createComposeRule()

    private var now by mutableFloatStateOf(0f)
    private var scene by mutableStateOf(Scene(OrbState.Idle, OrbCenter.None))

    private val outRoot: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "mascot-gifs").apply { mkdirs() }
    }

    private data class Scene(val state: OrbState, val center: OrbCenter)

    /** Um trecho: o estado, o que o app escreve no centro, e de quando a quando (ms) o relógio varre. */
    private class Clip(val slug: String, val state: OrbState, val center: OrbCenter, val fromMs: Int, val toMs: Int)

    private val clips = listOf(
        Clip("idle", OrbState.Idle, OrbCenter.None, 0, 3200), // uma piscada e uma tremida de orelha
        Clip("listening", OrbState.Listening, OrbCenter.Time(12), 10_600, 13_400), // atento: orelhas em pé, uma tremida, um olhar
        Clip("paused", OrbState.Paused, OrbCenter.PauseGlyph(12), 1_100, 3_900), // sonolento: a piscada lenta
        Clip("transcribing", OrbState.Transcribing, OrbCenter.None, 0, 2_800), // pensando: olhos vagando para cima
    )

    @Test
    fun captureMascotFrames() {
        rule.setContent {
            KeyFlowTheme(dark = false) {
                // 156 px, o tamanho padrão da orbe (o slider vai de 110 a 280): o que o Yan vê no aparelho, não uma ampliação.
                val orb = with(LocalDensity.current) { 156.toDp() }
                Row(Modifier.testTag(SCENE), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    for (bg in listOf(Color.White, Color.Black)) {
                        Box(Modifier.size(orb + 24.dp).background(bg), contentAlignment = Alignment.Center) {
                            VoiceOrb(
                                state = scene.state,
                                modifier = Modifier.requiredSize(orb),
                                center = scene.center,
                                timeProvider = { now },
                                style = OrbStyle.MASCOT,
                            )
                        }
                    }
                }
            }
        }

        for (clip in clips) {
            val dir = File(outRoot, clip.slug).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            rule.runOnUiThread { scene = Scene(clip.state, clip.center) }
            var index = 0
            var t = clip.fromMs
            while (t <= clip.toMs) {
                rule.runOnUiThread { now = t.toFloat() }
                rule.waitForIdle()
                val bitmap = captureFrame()
                File(dir, "frame-%03d.png".format(index++)).outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                t += STEP_MS
            }
            File(dir, "scenario.txt").writeText(
                "estado: ${clip.state} | centro: ${clip.center} | relógio: ${clip.fromMs}..${clip.toMs} ms de ${STEP_MS} em ${STEP_MS} ms | quadros: $index",
            )
        }
    }

    /**
     * Um quadro da cena. No emulador do CI (`-gpu swiftshader_indirect`, 2 núcleos) o `captureToImage` às vezes estoura os 2 s que
     * dá para a janela redesenhar (visto com o 1º quadro depois de instalar), e o teste caía. Repete até 5 vezes, com a árvore ociosa entre elas.
     */
    private fun captureFrame(): android.graphics.Bitmap {
        var last: ComposeTimeoutException? = null
        repeat(CAPTURE_TRIES) {
            try {
                return rule.onNodeWithTag(SCENE).captureToImage().asAndroidBitmap()
            } catch (e: ComposeTimeoutException) {
                last = e
                rule.waitForIdle()
            }
        }
        throw last!!
    }

    private companion object {
        const val SCENE = "mascot-motion-scene"

        /** 50 ms = 20 quadros por segundo: o bastante para a piscada (160 ms) ter quadros e o GIF ficar pequeno. */
        const val STEP_MS = 50

        const val CAPTURE_TRIES = 5
    }
}
