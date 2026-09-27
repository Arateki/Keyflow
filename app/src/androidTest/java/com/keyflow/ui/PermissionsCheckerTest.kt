package com.keyflow.ui

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionsCheckerTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @Test
    fun snapshotReflectsGrantedMicrophoneAndOverlayAppOp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // GrantPermissionRule pode falhar silenciosamente no API 34 fresh; conceder explicitamente.
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")

        val status = PermissionsChecker().snapshot(context)

        assertTrue(status.microphoneGranted)
        assertTrue(status.overlayGranted)
    }

    /**
     * `keyboardEnabled` espelha o SISTEMA. O teste acima fixava `false`, o que só vale em aparelho
     * zerado: no A32 do Yan o KeyFlow é o teclado de verdade e a asserção caía sem haver defeito
     * (2026-09-08). Aqui o estado é CONTROLADO em vez de assumido — habilita, confirma que o snapshot
     * enxerga, e devolve o aparelho como estava.
     */
    @Test
    fun snapshotSeesTheKeyboardOnceItIsEnabledInTheSystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ime = "${context.packageName}/.ime.KeyFlowKeyboardService"
        val wasEnabled = shell("settings get secure enabled_input_methods").contains(ime)

        try {
            // Só mexe no sistema se precisar: `ime enable` reconfigura o InputMethodManagerService e
            // atrapalha o teste seguinte que lança uma Activity (SetupActivityTest ficou intermitente
            // com isso, 2026-09-08). No aparelho do Yan e no emulador do gate ele já está habilitado.
            if (!wasEnabled) shell("ime enable $ime")
            // A leitura do `Settings.Secure` no processo do app não é instantânea depois do shell.
            val seen = waitUntilTrue { PermissionsChecker().snapshot(context).keyboardEnabled }
            assertTrue("o snapshot não enxergou o KeyFlow habilitado", seen)
        } finally {
            if (!wasEnabled) shell("ime disable $ime")
        }
    }

    private fun waitUntilTrue(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun shell(command: String): String {
        val fd: ParcelFileDescriptor = InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        // Drenar o output garante que o comando terminou antes de retornar.
        return try {
            java.io.FileInputStream(fd.fileDescriptor).readBytes().decodeToString()
        } finally {
            fd.close()
        }
    }
}
