package com.keyflow.overlay

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.core.RecordingMode
import com.keyflow.data.SettingsRepository
import com.keyflow.ui.gallery.GalleryActivity
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Dirige o serviço por intents e observa a gravação real; o consentimento é negado pelo sistema. */
@RunWith(AndroidJUnit4::class)
class OverlayServiceVisionTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test fun togglePausaAntesDoConsentimentoENegacaoPreservaAudio() = exercise(RecordingMode.TOGGLE)
    @Test fun pttPausaAntesDoConsentimentoENegacaoPreservaAudio() = exercise(RecordingMode.PUSH_TO_TALK)

    private fun exercise(mode: RecordingMode) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val repository = SettingsRepository(context)
        val saved = repository.settingsFlow().first()
        val scenario = ActivityScenario.launch<GalleryActivity>(Intent(context, GalleryActivity::class.java).putExtra("route", "config-visao"))
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").close()
        try {
            repository.saveSettings(saved.copy(overlayRecordingMode = mode))
            repository.saveDisclosureConsent(DISCLOSURE_VERSION, false)
            OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
            withTimeout(15_000) { KeyFlowController.buttonState.first { it == ButtonState.RECORDING } }
            OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_VISION))
            withTimeout(15_000) { KeyFlowController.buttonState.first { it == ButtonState.PAUSED } }
            val deny = device.wait(Until.findObject(By.text(Pattern.compile("(?i)cancel|cancelar"))), 15_000)
            assertNotNull("consentimento não apareceu", deny)
            deny!!.click()
            device.wait(Until.gone(By.pkg("com.android.systemui").text(Pattern.compile("(?i).*start now.*|.*iniciar agora.*"))), 15_000)
            instrumentation.waitForIdleSync()
            assertEquals(ButtonState.PAUSED, KeyFlowController.buttonState.value)
            // Nova chamada pede consentimento de novo; gesto de voz cancela só a Visão e mantém PAUSED.
            OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_VISION))
            assertNotNull(device.wait(Until.findObject(By.text(Pattern.compile("(?i)cancel|cancelar"))), 15_000))
            OverlayService.start(context, OverlayService.actionIntent(context, RecordingNotification.ACTION_CANCEL))
            instrumentation.waitForIdleSync()
            device.pressBack()
            assertEquals(ButtonState.PAUSED, KeyFlowController.buttonState.value)
        } finally {
            OverlayService.stop(context)
            instrumentation.waitForIdleSync()
            repository.saveSettings(saved)
            scenario.close()
        }
    }
}
