package com.keyflow.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.StrictModeGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Abrir o teclado não pode fazer disco nem rede NA thread principal (StrictMode, ver [StrictModeGuard]): é o caminho que o
 * Android cronometra — o 1º quadro do teclado tem 1 s para sair, e no aparelho lento ele passava disso (esteira de
 * instabilidade, 2026-10-10). Violação nova no código do teclado (ou no que ele chama) falha aqui; as que já existiam estão
 * em [KNOWN], cada uma com o porquê, para a lista só encolher.
 */
@RunWith(AndroidJUnit4::class)
class StrictModeKeyboardTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val userSettings = UserSettingsGuard(context)

    @Before
    fun setUp() {
        assumeTrue("o coletor do StrictMode precisa do penaltyListener (Android 9+)", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        userSettings.capture()
        userSettings.forceExpanded()
    }

    @After
    fun tearDown() {
        userSettings.restore()
        device.pressHome()
    }

    @Test
    fun openingTheKeyboardDoesNoDiskOrNetworkOnTheMainThread() {
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("ime enable $IME")
        shell("ime set $IME")
        StrictModeGuard.clear()

        val intent = Intent().setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        device.wait(Until.hasObject(By.desc("ime_probe_field")), TIMEOUT)
        device.findObject(By.desc("ime_probe_field"))?.click()
        awaitKeyboardAttached(device) { context.startActivity(intent) }
        // O listener do StrictMode entrega numa thread própria: dá um instante para o que aconteceu na abertura chegar.
        SystemClock.sleep(1_000)

        val ours = StrictModeGuard.violations
            .filter { it.thread == "main" }
            .mapNotNull { it.ourFrame?.substringBeforeLast(":") }
            .filter { frame -> KEYBOARD_PATH.any { frame.startsWith(it) } }
            .toSortedSet()
        assertEquals(
            "disco/rede novo na thread principal ao abrir o teclado — tire da thread principal ou anote em KNOWN com o porquê",
            emptySet<String>(),
            ours - KNOWN,
        )
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    private companion object {
        const val IME = "com.keyflow/.ime.KeyFlowKeyboardService"
        const val TIMEOUT = 5000L

        /** O código do teclado e o que ele usa: violação de outro canto do processo (o probe, o app) não conta aqui. */
        val KEYBOARD_PATH = listOf("com.keyflow.ime.", "com.keyflow.data.", "com.keyflow.skins.", "com.keyflow.core.")

        /** As violações que já existiam quando a guarda entrou (2026-10-10). Corrigir = tirar daqui. */
        val KNOWN = emptySet<String>()
    }
}
