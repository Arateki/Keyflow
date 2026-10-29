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
import com.keyflow.vision.VisionFlowState
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Dirige o serviço por intents e observa a gravação real; o consentimento é negado pelo sistema. */
@RunWith(AndroidJUnit4::class)
class OverlayServiceVisionTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @Test fun togglePausaAntesDoConsentimentoENegacaoPreservaAudio() = exercise(RecordingMode.TOGGLE)
    @Test fun pttPausaAntesDoConsentimentoENegacaoPreservaAudio() = exercise(RecordingMode.PUSH_TO_TALK)

    @Test fun janelaRecusadaEncerraFluxoSemDerrubarServico() = withReadyService { service ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_VISION))
        withTimeout(15_000) { OverlayService.visionFlowStateForTest.first { it == VisionFlowState.CONSENT } }
        assertNotNull(device.wait(Until.findObject(By.text(Pattern.compile("(?i).*start now.*|.*iniciar agora.*"))), 15_000))
        OverlayService.visionRestartShowForTest = { throw android.view.WindowManager.BadTokenException("permissão revogada entre criar e mostrar") }
        try {
            instrumentation.runOnMainSync { service.showVisionRestart() }
            withTimeout(15_000) { OverlayService.visionFlowStateForTest.first { it == VisionFlowState.NONE } }
            assertTrue(device.wait(Until.gone(By.text(Pattern.compile("(?i).*start now.*|.*iniciar agora.*"))), 15_000))
            assertEquals(ButtonState.PAUSED, KeyFlowController.buttonState.value)
        } finally { OverlayService.visionRestartShowForTest = null }
    }

    @Test fun falhaAoFecharProjecaoAindaLimpaCacheERestauraViews() = withReadyService { service ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cache = com.keyflow.vision.client.VisionCache.dir(instrumentation.targetContext).apply { mkdirs() }
        java.io.File(cache, "regressao.txt").writeText("dado sintético")
        val visible = android.view.View(instrumentation.targetContext)
        val gone = android.view.View(instrumentation.targetContext).apply { visibility = android.view.View.GONE }
        OverlayService.visionSessionCloseForTest = { throw SecurityException("microfone revogado ao restaurar FGS") }
        try {
            instrumentation.runOnMainSync {
                service.hideVisionViews(listOf(visible, gone))
                service.cleanupVision()
                assertEquals(android.view.View.VISIBLE, visible.visibility)
                assertEquals(android.view.View.GONE, gone.visibility)
            }
            assertTrue("cache retido após falha na sessão", cache.listFiles().orEmpty().isEmpty())
        } finally { OverlayService.visionSessionCloseForTest = null }
    }

    @Test fun destruirServicoNaoSolicitaRestauracaoDoForeground() {
        val closed = java.util.concurrent.CountDownLatch(1)
        var restore = true
        try {
            withReadyService {
                OverlayService.visionSessionCloseForTest = { requested -> restore = requested; closed.countDown() }
            }
            assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(false, restore)
        } finally { OverlayService.visionSessionCloseForTest = null }
    }

    private fun withReadyService(block: suspend (OverlayService) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = SettingsRepository(context)
        val saved = repository.settingsFlow().first()
        val scenario = ActivityScenario.launch<GalleryActivity>(Intent(context, GalleryActivity::class.java).putExtra("route", "config-visao"))
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").close()
        try {
            repository.saveDisclosureConsent(DISCLOSURE_VERSION, false)
            OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
            withTimeout(15_000) { KeyFlowController.buttonState.first { it == ButtonState.RECORDING } }
            block(checkNotNull(OverlayService.instanceForVisionTest))
        } finally {
            if (OverlayService.visionFlowStateForTest.value != VisionFlowState.NONE) {
                OverlayService.start(context, OverlayService.actionIntent(context, RecordingNotification.ACTION_CANCEL))
                withTimeout(15_000) { OverlayService.visionFlowStateForTest.first { it == VisionFlowState.NONE } }
            }
            if (KeyFlowController.buttonState.value in listOf(ButtonState.PAUSED, ButtonState.RECORDING)) {
                OverlayService.start(context, OverlayService.actionIntent(context, RecordingNotification.ACTION_CANCEL))
                withTimeout(15_000) { KeyFlowController.buttonState.first { it == ButtonState.CANCELLING } }
            }
            if (KeyFlowController.buttonState.value == ButtonState.CANCELLING) {
                OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_COMMIT_CANCEL))
                withTimeout(15_000) { KeyFlowController.buttonState.first { it == ButtonState.IDLE_READY || it == ButtonState.IDLE_NO_FIELD } }
            }
            OverlayService.stop(context)
            instrumentation.waitForIdleSync()
            repository.saveSettings(saved)
            scenario.close()
        }
    }

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
            withTimeout(15_000) { OverlayService.visionFlowStateForTest.first { it == VisionFlowState.NONE } }
            instrumentation.waitForIdleSync()
            assertEquals(ButtonState.PAUSED, KeyFlowController.buttonState.value)
            // Nova chamada pede consentimento de novo; gesto de voz cancela só a Visão e mantém PAUSED.
            OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_VISION))
            assertNotNull(device.wait(Until.findObject(By.text(Pattern.compile("(?i)cancel|cancelar"))), 15_000))
            val voiceAction = if (mode == RecordingMode.TOGGLE) OverlayService.ACTION_RECORD else RecordingNotification.ACTION_CANCEL
            OverlayService.start(context, OverlayService.actionIntent(context, voiceAction))
            withTimeout(15_000) { OverlayService.visionFlowStateForTest.first { it == VisionFlowState.NONE } }
            instrumentation.waitForIdleSync()
            assertTrue("cancelar a etapa deixou o diálogo de captura aberto", device.wait(
                Until.gone(By.pkg("com.android.systemui").text(Pattern.compile("(?i).*start now.*|.*iniciar agora.*"))), 15_000,
            ))
            assertEquals(ButtonState.PAUSED, KeyFlowController.buttonState.value)
        } finally {
            // Encerra o fluxo e descarta a gravação de teste pelo caminho público antes de parar o serviço.
            if (OverlayService.visionFlowStateForTest.value != VisionFlowState.NONE) {
                OverlayService.start(context, OverlayService.actionIntent(context, RecordingNotification.ACTION_CANCEL))
                withTimeout(15_000) { OverlayService.visionFlowStateForTest.first { it == VisionFlowState.NONE } }
            }
            if (KeyFlowController.buttonState.value in listOf(ButtonState.PAUSED, ButtonState.RECORDING)) {
                OverlayService.start(context, OverlayService.actionIntent(context, RecordingNotification.ACTION_CANCEL))
                withTimeout(15_000) { KeyFlowController.buttonState.first { it == ButtonState.CANCELLING } }
            }
            if (KeyFlowController.buttonState.value == ButtonState.CANCELLING) {
                OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_COMMIT_CANCEL))
                withTimeout(15_000) { KeyFlowController.buttonState.first { it == ButtonState.IDLE_READY || it == ButtonState.IDLE_NO_FIELD } }
            }
            OverlayService.stop(context)
            instrumentation.waitForIdleSync()
            repository.saveSettings(saved)
            scenario.close()
        }
    }
}
