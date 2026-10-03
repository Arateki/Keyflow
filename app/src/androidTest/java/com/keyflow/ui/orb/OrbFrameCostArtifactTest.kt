package com.keyflow.ui.orb

import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.ComposeSmokeActivity
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Mede o custo de quadro de cada skin da orbe **animando em tempo real** (o relógio de verdade, sem `timeProvider`): sobe uma
 * activity com uma orbe de 195 px (o tamanho padrão), deixa 2 s de aquecimento, zera o `dumpsys gfxinfo` e lê as percentis depois
 * de 8 s. Só roda com `-e orbFrameCost 1`. Cada configuração roda `rounds` vezes (argumento `-e rounds N`, padrão 2), em ordem diferente a cada rodada, para
 * separar o custo da skin do ruído do emulador.
 *
 * Não afirma nada: é uma medição, escrita no logcat (tag `OrbFrameCost`) e em `externalFilesDir/orb-frame-cost.txt`. Num emulador
 * o `gfxinfo` mede a renderização do emulador (por software ou pela GPU do host), não a do aparelho — serve para comparar uma skin
 * com a outra no mesmo ambiente, não para dizer quantos quadros o A32 faz.
 */
@RunWith(AndroidJUnit4::class)
class OrbFrameCostArtifactTest {

    private class Case(val name: String, val style: OrbStyle, val state: OrbState, val center: OrbCenter)

    private val cases = listOf(
        Case("Ateliê repouso", OrbStyle.ATELIE, OrbState.Idle, OrbCenter.None),
        Case("Aero repouso", OrbStyle.AERO, OrbState.Idle, OrbCenter.None),
        Case("Glitch repouso", OrbStyle.GLITCH, OrbState.Idle, OrbCenter.None),
        Case("Rose Gold repouso", OrbStyle.ROSE_GOLD, OrbState.Idle, OrbCenter.None),
        Case("Mascote repouso", OrbStyle.MASCOT, OrbState.Idle, OrbCenter.None),
        Case("Ateliê gravando", OrbStyle.ATELIE, OrbState.Listening, OrbCenter.Time(12)),
        Case("Aero gravando", OrbStyle.AERO, OrbState.Listening, OrbCenter.Time(12)),
        Case("Rose Gold gravando", OrbStyle.ROSE_GOLD, OrbState.Listening, OrbCenter.Time(12)),
        Case("Mascote gravando", OrbStyle.MASCOT, OrbState.Listening, OrbCenter.Time(12)),
        Case("Mascote pensando", OrbStyle.MASCOT, OrbState.Transcribing, OrbCenter.None),
    )

    @Test
    fun measureFrameCost() {
        // Leva uns 4 minutos e não afirma nada: só roda quando pedida (`-e orbFrameCost 1`), não junto com a suíte.
        Assume.assumeTrue("medição sob demanda: passe -e orbFrameCost 1", InstrumentationRegistry.getArguments().getString("orbFrameCost") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rounds = InstrumentationRegistry.getArguments().getString("rounds")?.toIntOrNull() ?: 2
        val lines = ArrayList<String>()
        for (round in 0 until rounds) {
            // A ordem muda a cada rodada (a anterior ao contrário): quem rodou logo depois de uma skin pesada não fica sempre o mesmo.
            val order = if (round % 2 == 0) cases else cases.reversed()
            for (case in order) {
                val line = "rodada ${round + 1} | ${case.name} | " + measure(case)
                lines.add(line)
                Log.i(TAG, line)
            }
        }
        val out = File(instrumentation.targetContext.getExternalFilesDir(null), "orb-frame-cost.txt")
        out.writeText(lines.joinToString("\n") + "\n")
    }

    private fun measure(case: Case): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(ComposeSmokeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    // 195 px, o padrão do slider da aba Sobrepor, sobre branco (o caso mais caro para o que desenha por cima do fundo).
                    val side = with(LocalDensity.current) { 195.toDp() }
                    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                        VoiceOrb(case.state, Modifier.requiredSize(side), center = case.center, style = case.style)
                    }
                }
            }
            Thread.sleep(WARMUP_MS)
            shell("dumpsys gfxinfo ${instrumentation.targetContext.packageName} reset")
            Thread.sleep(MEASURE_MS)
            val dump = shell("dumpsys gfxinfo ${instrumentation.targetContext.packageName}")
            return summarize(dump)
        }
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }

    /** Fica só com o que importa do `gfxinfo`: quadros, quadros lentos e as percentis de tempo de quadro (e da GPU, se houver). */
    private fun summarize(dump: String): String {
        val wanted = Regex("""(Total frames rendered: \d+|Janky frames: [^\n]*|\d+th (?:gpu )?percentile: \d+ms)""")
        val seen = LinkedHashSet<String>()
        for (m in wanted.findAll(dump)) if (!m.value.startsWith("Janky frames (legacy)")) seen.add(m.value.trim())
        return seen.joinToString(" | ")
    }

    private companion object {
        const val TAG = "OrbFrameCost"
        const val WARMUP_MS = 2_000L
        const val MEASURE_MS = 8_000L
    }
}
