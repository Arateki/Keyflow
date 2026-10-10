package com.keyflow.verify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.overlay.OverlayService
import com.keyflow.overlay.RecordingNotification
import com.keyflow.core.ButtonState
import com.keyflow.data.SettingsRepository
import com.keyflow.ime.UserSettingsGuard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captura as três superfícies de sistema reais (VERIFY-03): IME sobre um campo, overlay sobre um app
 * e a notificação de gravação. Cada teste é autocontido (monta + limpa) e salva
 * `verification/sys-*.png` no `externalFilesDir` (puxado para o host pelo gate `screenshot`).
 */
@RunWith(AndroidJUnit4::class)
class SystemSurfacesScreenshotTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    // Preserva o que o dono do aparelho já tinha configurado — ver [UserSettingsGuard]. `ime reset`
    // devolveria o teclado de fábrica, não o que a pessoa usava.
    private val userSettings = UserSettingsGuard(context)

    private val outDir: File by lazy {
        File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
    }

    /**
     * Pré-aquece o DataStore + Android Keystore (EncryptedSharedPreferences) lendo as settings uma vez
     * antes do teste, no mesmo arquivo/contexto que `OverlayService.onCreate()` vai usar (a inicialização
     * do Keystore na 1ª leitura é cara e pode estourar a janela de `startForeground` em `sysOverlayOverApp`
     * — ver `ForegroundServiceDidNotStartInTimeException`).
     */
    @Before
    fun warmUpSettings() {
        userSettings.capture()
        runBlocking { SettingsRepository(context).settingsFlow().first() }
    }

    // Pareia com o `capture()` acima para TODOS os testes da classe. Antes o `restore()` morava só no
    // `finally` de `sysImeOverField`, e os outros dois capturavam sem devolver — inócuo enquanto o
    // capture só lia, virou vazamento quando ele passou a forçar `show_ime_with_hard_keyboard`
    // (2026-09-09): a chave ficava em 1 no aparelho depois da suíte.
    @After
    fun restoreUserSettings() {
        userSettings.restore()
    }

    @Test
    fun sysNotification() {
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        createChannel()
        val notification = RecordingNotification(context, CHANNEL).build(ButtonState.RECORDING)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            manager.notify(NOTIF_ID, notification)
            device.openNotification()
            device.wait(Until.hasObject(By.textContains("Gravando")), TIMEOUT)
            Thread.sleep(800)
            capture("sys-notification")
        } finally {
            manager.cancel(NOTIF_ID)
            device.pressBack()
            device.pressHome()
        }
    }

    @Test
    fun sysOverlayOverApp() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        try {
            device.pressHome()
            // `intent()` (sem action) NÃO promove o serviço: `onStartCommand` casa por action, e a única
            // outra via é `onCreate` — que só sobe a orbe se o Modo Sobrepor estiver ligado nas settings
            // de quem usa. Com ele desligado o serviço nunca chamava `startForeground`, este teste caía
            // em "OverlayService não subiu" e ~10s depois o sistema matava o PROCESSO com
            // ForegroundServiceDidNotStartInTimeException, derrubando de carona o teste seguinte
            // (2026-09-08). `startOverlayIntent` é o que o app usa de verdade e independe da setting.
            OverlayService.start(context, OverlayService.startOverlayIntent(context))
            waitUntil("OverlayService não subiu") { OverlayService.isRunningForUi }
            Thread.sleep(1200) // deixa o ComposeView do orbe compor
            capture("sys-overlay-over-app")
        } finally {
            OverlayService.stop(context)
            waitUntil("OverlayService não encerrou", timeoutMs = 5000) { !OverlayService.isRunningForUi }
        }
    }

    @Test
    fun sysImeOverField() {
        shell("ime enable $IME")
        shell("ime set $IME")
        try {
            val intent = Intent()
                .setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)

            // O campo pede foco e SOFT_INPUT_STATE_ALWAYS_VISIBLE; toca p/ garantir o IME.
            device.wait(Until.hasObject(By.desc("ime_probe_field")), TIMEOUT)
            device.findObject(By.desc("ime_probe_field"))?.click()
            // Espera uma tecla do nosso teclado QWERTY aparecer.
            assertTrue(
                "teclado KeyFlow não apareceu",
                device.wait(Until.hasObject(By.text("espaço")), TIMEOUT) != null,
            )
            Thread.sleep(600)
            capture("sys-ime-over-field")
        } finally {
            device.pressHome()
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    // A captura do sistema às vezes falha no emulador do CI (2026-10-10, swangle): tenta de novo antes de desistir.
    private fun capture(name: String) {
        val file = File(outDir, "$name.png")
        val captured = (1..CAPTURE_TRIES).any { attempt ->
            device.takeScreenshot(file) || run {
                if (attempt < CAPTURE_TRIES) SystemClock.sleep(500)
                false
            }
        }
        assertTrue("falha ao capturar $name ($CAPTURE_TRIES tentativas)", captured)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "KeyFlow overlay", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun waitUntil(message: String, timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
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
        const val CHANNEL = "keyflow_overlay"
        const val NOTIF_ID = 1001
        const val IME = "com.keyflow/.ime.KeyFlowKeyboardService"
        const val TIMEOUT = 5000L
        const val CAPTURE_TRIES = 3
    }
}
