package com.keyflow.ui

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
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
        assertFalse(status.keyboardEnabled)
    }

    private fun shell(command: String) {
        val fd: ParcelFileDescriptor = InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        // Drenar o output garante que o comando terminou antes de retornar.
        try {
            java.io.FileInputStream(fd.fileDescriptor).readBytes()
        } finally {
            fd.close()
        }
    }
}
