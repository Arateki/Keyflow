package com.keyflow.ime

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Medição, não verificação de comportamento: abre o campo do probe várias vezes e, durante cada abertura, compara onde o
 * sistema de entrada vê a janela do teclado (`dumpsys input`) com onde o gerenciador de janelas a pôs (`dumpsys window`).
 * Vale para qualquer teclado — é o controle com o Gboard da esteira de instabilidade.
 *
 * Esteira, 2026-10-10: no emulador sob carga a janela do KeyFlow chegou a ficar 126 px abaixo ou no topo da tela, com o
 * gerenciador de janelas guardando a posição certa (as capturas mostraram o teclado desenhado fora do lugar). Este teste diz
 * se o Gboard, no mesmo emulador e no mesmo campo, faz o mesmo — do Android/emulador — ou se é coisa do KeyFlow.
 *
 * Só roda com `-e placementProbe true` (a esteira passa; o gate não). `-e placementIme gboard|keyflow` escolhe o teclado e
 * `-e probeProcess remoto|local` o campo (ver KeyFlowKeyboardCanvasGridTest.probeActivity).
 */
@RunWith(AndroidJUnit4::class)
class ImeWindowPlacementTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val args get() = InstrumentationRegistry.getArguments()
    private var previousIme: String? = null

    @Before
    fun onlyWhenAsked() {
        assumeTrue("medição da esteira: só com -e placementProbe true", args.getString("placementProbe") == "true")
        previousIme = shell("settings get secure default_input_method").trim()
    }

    @After
    fun restoreIme() {
        previousIme?.takeIf { it.isNotEmpty() && it != "null" }?.let { shell("ime set $it") }
        device.pressHome()
    }

    @Test
    fun theImeWindowIsWhereTheWindowManagerPutIt() {
        val ime = if (args.getString("placementIme") == "gboard") GBOARD else KEYFLOW
        selectIme(ime)
        val probe = if (args.getString("probeProcess") == "remoto") "com.keyflow.ui.ImeProbeRemoteActivity"
        else "com.keyflow.ui.ImeProbeActivity"
        val openings = args.getString("placementOpenings")?.toIntOrNull() ?: OPENINGS

        var samples = 0
        var displacedSamples = 0
        val displacedOpenings = mutableListOf<String>()
        repeat(openings) { opening ->
            device.pressHome()
            SystemClock.sleep(1_500)
            context.startActivity(
                Intent().setClassName(context.packageName, probe)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            device.wait(Until.hasObject(By.desc("ime_probe_field")), 5_000)
            runCatching { device.findObject(By.desc("ime_probe_field"))?.click() }

            // Lê durante a abertura; fora do lugar = visível na tela e diferente do gerenciador de janelas, em 2 leituras
            // seguidas (a janela entrando por baixo da tela é o normal antes de aparecer e não conta).
            var streak = 0
            val seen = mutableListOf<String>()
            val end = SystemClock.uptimeMillis() + SAMPLE_MS
            while (SystemClock.uptimeMillis() < end) {
                val input = imeInputFrame(device)
                val wm = windowManagerImeFrame()
                if (input == null || wm == null) {
                    SystemClock.sleep(50)
                    continue
                }
                samples++
                val inputTop = top(input)
                if (input != wm && inputTop != null && inputTop < device.displayHeight) {
                    displacedSamples++
                    seen += "entrada=$input wm=$wm"
                    if (++streak == 2) displacedOpenings += "abertura ${opening + 1}: ${seen.takeLast(2).joinToString(" → ")}"
                } else {
                    streak = 0
                }
            }
            Log.i(TAG, "abertura ${opening + 1}/$openings ($ime): ${seen.size} leituras fora do lugar")
        }

        Log.i(TAG, "$ime: $displacedSamples de $samples leituras fora do lugar; aberturas deslocadas: $displacedOpenings")
        assertEquals(
            "janela do teclado fora do lugar ($ime, ${args.getString("probeProcess") ?: "local"}): " +
                "${displacedOpenings.size} de $openings aberturas, $displacedSamples de $samples leituras — $displacedOpenings",
            0,
            displacedOpenings.size,
        )
    }

    private fun selectIme(ime: String) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            shell("ime enable $ime")
            shell("ime set $ime")
            if (shell("settings get secure default_input_method").trim() == ime) return
            SystemClock.sleep(1_000)
        }
        throw AssertionError("não consegui selecionar o teclado $ime")
    }

    /** O frame da janela do IME para o gerenciador de janelas, se ela estiver visível. Sem Regex com chaves (ICU). */
    private fun windowManagerImeFrame(): String? {
        val lines = shell("dumpsys window windows").lines()
        val isHeader = { line: String -> line.trim().startsWith("Window #") && line.contains(" Window{") }
        val start = lines.indexOfFirst { isHeader(it) && it.contains("InputMethod}") }
        if (start < 0) return null
        val block = lines.drop(start + 1).takeWhile { !isHeader(it) }
        if (block.none { it.trim() == "isVisible=true" }) return null
        val frames = block.firstOrNull { it.trim().startsWith("Frames:") } ?: return null
        return Regex("frame=(\\[[^]]*]\\[[^]]*])").find(frames)?.groupValues?.get(1)
    }

    private fun top(frame: String): Int? = frame.substringAfter(",").substringBefore("]").toIntOrNull()

    private fun shell(command: String): String {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        try {
            return String(java.io.FileInputStream(fd.fileDescriptor).readBytes())
        } finally {
            fd.close()
        }
    }

    private companion object {
        const val TAG = "KeyFlow.ImePlacement"
        const val KEYFLOW = "com.arateki.keyflow/com.keyflow.ime.KeyFlowKeyboardService"
        const val GBOARD = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
        const val OPENINGS = 8
        const val SAMPLE_MS = 4_000L
    }
}
