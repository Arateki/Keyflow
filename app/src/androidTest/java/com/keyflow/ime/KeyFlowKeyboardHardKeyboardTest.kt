package com.keyflow.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Com um teclado FÍSICO conectado, o `onEvaluateInputViewShown()` padrão do `InputMethodService` esconde
 * o teclado virtual — some o teclado do KeyFlow e, junto, o mic, que é o produto. O app passa a decidir
 * isso por conta própria (pedido do Yan, 2026-09-09), com um controle na aba Teclado.
 *
 * Este teste zera `show_ime_with_hard_keyboard` de propósito: com a chave em 1 o sistema já mandaria
 * mostrar e o teste passaria sem exercitar nada. O [UserSettingsGuard] a força em 1 para os demais
 * testes de teclado; aqui ela é zerada DEPOIS do `capture()`, e o `restore()` devolve o valor original.
 *
 * Só tem o que medir onde o aparelho declara teclado físico — é o caso do emulador Android 17, que
 * reporta `qwerty` mesmo com `hw.keyboard = no` no AVD. No A32 e no emulador API 34 (`nokeys`) ele é
 * pulado, em vez de passar vazio.
 */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardHardKeyboardTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    private val userSettings = UserSettingsGuard(context)
    private val repo get() = SettingsRepository(context)
    private var savedFlag: Boolean? = null

    @Before
    fun setUp() {
        assumeTrue(
            "aparelho não declara teclado físico — nada a medir aqui",
            context.resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS,
        )
        userSettings.capture()
        // O ponto do teste: sem isto o sistema mostraria o teclado sozinho.
        shell("settings put secure show_ime_with_hard_keyboard 0")
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("ime enable $IME")
        shell("ime set $IME")
        savedFlag = runBlocking { repo.settingsFlow().first().keyboardShowWithHardKeyboard }
    }

    @After
    fun tearDown() {
        savedFlag?.let { saved ->
            runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(keyboardShowWithHardKeyboard = saved)) }
        }
        userSettings.restore()
        device.pressHome()
    }

    @Test
    fun keyboardStillShowsWhenDeviceDeclaresAHardKeyboard() {
        val intent = Intent()
            .setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        device.wait(Until.hasObject(By.desc("ime_probe_field")), TIMEOUT)
        device.findObject(By.desc("ime_probe_field"))?.click()

        val deadline = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < deadline && !inputViewShown()) {
            Thread.sleep(100)
        }
        assertTrue("o teclado não apareceu com teclado físico declarado", inputViewShown())
    }

    /**
     * O contrário: desmarcado, o KeyFlow volta a respeitar o sistema e some. Sem este teste, um `true`
     * fixo em `onEvaluateInputViewShown()` passaria no teste de cima para sempre.
     */
    @Test
    fun keyboardStaysHiddenWhenTheSettingIsOff() {
        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(keyboardShowWithHardKeyboard = false)) }

        val intent = Intent()
            .setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        device.wait(Until.hasObject(By.desc("ime_probe_field")), TIMEOUT)
        device.findObject(By.desc("ime_probe_field"))?.click()

        // Espera o tempo inteiro: aqui o que se afirma é a AUSÊNCIA, então sair cedo não vale.
        Thread.sleep(TIMEOUT)
        assertFalse("desmarcado, o teclado não deveria aparecer com teclado físico declarado", inputViewShown())
    }

    /**
     * Estado real da plataforma, não o estático do serviço: `activeViewForTest` sobrevive ao teste
     * anterior (o serviço não é destruído entre testes), então afirmar ausência com ele daria falso
     * negativo. `mIsInputViewShown` é o que o `InputMethodService` de fato decidiu — e é o mesmo campo
     * que distinguiu produto de harness ao diagnosticar isto (2026-09-09).
     */
    private fun inputViewShown(): Boolean =
        shell("dumpsys input_method")
            .lineSequence()
            .firstOrNull { it.contains("mIsInputViewShown=") }
            ?.substringAfter("mIsInputViewShown=")
            ?.startsWith("true") == true

    private fun shell(command: String): String {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return try {
            java.io.FileInputStream(fd.fileDescriptor).readBytes().decodeToString()
        } finally {
            fd.close()
        }
    }

    private companion object {
        const val IME = "com.arateki.keyflow/com.keyflow.ime.KeyFlowKeyboardService"
        const val TIMEOUT = 8_000L
    }
}
