package com.keyflow.testing

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.SettingsRepository
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.RunListener
import java.io.File

/**
 * Faz a suíte instrumentada inteira rodar sobre a config de fábrica do app e devolve a config de quem é
 * dono do aparelho no fim — ver [DeviceSettingsSnapshot].
 *
 * Registrado como `listener` no `<instrumentation>` do manifest de teste, então vale tanto para o
 * `connectedDebugAndroidTest` quanto para o `am instrument` direto usado no A32.
 */
class FactorySettingsListener : RunListener() {

    override fun testRunStarted(description: Description?) {
        val snapshot = snapshot()
        val recovering = snapshot.hasPendingSnapshot
        snapshot.enterFactoryState()
        Log.i(TAG, if (recovering) "config do dono já guardada (rodada anterior interrompida); suíte em estado de fábrica" else "config do dono guardada; suíte em estado de fábrica")
    }

    override fun testRunFinished(result: Result?) {
        if (snapshot().restoreOwnerState()) Log.i(TAG, "config do dono devolvida")
    }

    private fun snapshot(): DeviceSettingsSnapshot {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return DeviceSettingsSnapshot(
            repo = SettingsRepository(context),
            uiPrefs = context.getSharedPreferences(KEYBOARD_UI_PREFS, Context.MODE_PRIVATE),
            file = File(context.filesDir, SNAPSHOT_FILE),
            shell = ::shell,
        )
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes().decodeToString() }
    }

    companion object {
        const val TAG = "FactorySettings"
        /** Prefs do IME (`KeyFlowKeyboardService.uiPrefs`): expandido/recolhido. */
        const val KEYBOARD_UI_PREFS = "keyflow_kb_ui"
        const val SNAPSHOT_FILE = "instrumentation-owner-settings.json"
    }
}
