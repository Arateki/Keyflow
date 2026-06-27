package com.keyflow.ui

import android.Manifest
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.config.TOGGLE_OVERLAY_MODE_TAG
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fiação real da [SetupActivity] em Compose (T10). Cobre a parte de produção que sobrou da conversão:
 * o toggle de **Modo Overlay** inicia/encerra o [OverlayService] (FGS) — herdando a cobertura do antigo
 * "Ativar botão flutuante".
 */
@RunWith(AndroidJUnit4::class)
class SetupActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<SetupActivity>()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        context.stopService(OverlayService.intent(context))
    }

    @After
    fun tearDown() {
        context.stopService(OverlayService.intent(context))
    }

    @Test
    fun overlayModeToggleStartsAndStopsForegroundService() {
        // O orbe do header anima infinitamente; sem isto o link Compose-Espresso nunca fica idle.
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()
        waitUntil { OverlayService.isRunningForUi }

        // Deixa o round-trip do DataStore refletir o flag (checked=on) antes de desligar.
        SystemClock.sleep(800)
        compose.waitForIdle()

        compose.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()
        waitUntil { !OverlayService.isRunningForUi }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Condition was not met before timeout")
    }

    private fun shell(command: String) {
        val fd: ParcelFileDescriptor = InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        fd.close()
    }
}
