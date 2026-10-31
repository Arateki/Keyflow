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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** Capturar de um fluxo (FLX-19): nunca pausa nem mexe numa gravação em curso, e o consentimento negado entrega `null` ao fluxo. */
    @Test fun capturaDeFluxoRecusaGravacaoEEntregaNullAoNegarOConsentimento() = withReadyService { service ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val entregas = java.util.Collections.synchronizedList(mutableListOf<android.graphics.Bitmap?>())
        suspend fun fase(etapa: String, estado: VisionFlowState) {
            withTimeoutOrNull(15_000) { OverlayService.visionFlowStateForTest.first { it == estado } }
                ?: throw AssertionError("esperando a Visão em $estado ($etapa); está em ${OverlayService.visionFlowStateForTest.value}, botão ${KeyFlowController.buttonState.value}")
        }
        suspend fun botao(etapa: String, vararg estados: ButtonState) {
            withTimeoutOrNull(15_000) { KeyFlowController.buttonState.first { it in estados } }
                ?: throw AssertionError("esperando o botão em ${estados.toList()} ($etapa); está em ${KeyFlowController.buttonState.value}")
        }
        var aceitou = true
        // Com a gravação em curso (RECORDING) o fluxo é recusado, sem pausar nada nem abrir o consentimento.
        instrumentation.runOnMainSync { aceitou = service.capturarParaFluxo { entregas += it } }
        assertEquals(false, aceitou)
        assertEquals(VisionFlowState.NONE, OverlayService.visionFlowStateForTest.value)
        assertEquals(ButtonState.RECORDING, KeyFlowController.buttonState.value)
        assertTrue(entregas.isEmpty())
        // Sobe também a orbe: sem ela o serviço do modo só-teclado se encerra ao voltar ao repouso (é a T14 que o segura durante o fluxo).
        OverlayService.start(context, OverlayService.startOverlayIntent(context))
        withTimeoutOrNull(15_000) { while (!OverlayService.isRunningForUi) kotlinx.coroutines.delay(50) } ?: throw AssertionError("a orbe não subiu")
        // As ações vão por startService (como OverlayServiceCancelTest): OverlayService.start marcaria um primeiro plano pendente que nunca chega,
        // e o stop do fim ficaria só anotado.
        // Descarta a gravação: em repouso a captura do fluxo começa e pede o consentimento.
        context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_CANCEL))
        botao("cancelar a gravação", ButtonState.CANCELLING)
        context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_COMMIT_CANCEL))
        botao("concluir o cancelamento", ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD)
        instrumentation.runOnMainSync { aceitou = service.capturarParaFluxo { entregas += it } }
        assertTrue(aceitou)
        fase("pedido do fluxo", VisionFlowState.CONSENT)
        val deny = device.wait(Until.findObject(By.text(Pattern.compile("(?i)cancel|cancelar"))), 15_000)
        assertNotNull("consentimento não apareceu", deny)
        deny!!.click()
        fase("negação do consentimento", VisionFlowState.NONE)
        instrumentation.waitForIdleSync()
        assertEquals("negar o consentimento entrega null uma vez só", listOf<android.graphics.Bitmap?>(null), entregas.toList())
        assertTrue(KeyFlowController.buttonState.value in listOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD))
        // O destino de fluxo não vaza para o pedido seguinte: um pedido sem destino segue o caminho de sempre (nada ao fluxo).
        context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_VISION))
        fase("pedido de gestos", VisionFlowState.CONSENT)
        context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
        fase("fim do pedido de gestos", VisionFlowState.NONE)
        assertEquals(1, entregas.size)
        // Desliga a orbe que este teste subiu e espera ela sair: o próximo teste começa com o serviço de fato parado.
        OverlayService.stop(context)
        withTimeoutOrNull(15_000) { while (OverlayService.isRunningForUi) kotlinx.coroutines.delay(50) } ?: throw AssertionError("a orbe não desligou")
    }

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

    @Test fun capturaOcultaJanelaRealDoTecladoERestauraMesmoComFalhaNaLimpeza() = withReadyService { service ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val userSettings = com.keyflow.ime.UserSettingsGuard(context)
        fun imeState(): String {
            var state = ""
            instrumentation.runOnMainSync {
                val view = com.keyflow.ime.KeyFlowKeyboardService.activeViewForTest
                state = "controller=${KeyFlowController.keyboardVisible.value}, window=${view?.windowVisibility}, shown=${view?.isShown}, attached=${view?.isAttachedToWindow}"
            }
            return state
        }
        suspend fun awaitKeyboard(visible: Boolean, stage: String, timeoutMs: Long = 10_000) {
            try { withTimeout(timeoutMs) { KeyFlowController.keyboardVisible.first { it == visible } } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("IME não mudou o controller para $visible em $stage: ${imeState()}", error)
            }
        }
        suspend fun awaitWindow(visible: Boolean, stage: String, action: (() -> Unit)? = null) {
            val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
            var observer: android.view.ViewTreeObserver? = null
            val listener = android.view.ViewTreeObserver.OnWindowVisibilityChangeListener { visibility ->
                if ((visibility == android.view.View.VISIBLE) == visible) ready.complete(Unit)
            }
            instrumentation.runOnMainSync {
                val view = checkNotNull(com.keyflow.ime.KeyFlowKeyboardService.activeViewForTest)
                observer = view.viewTreeObserver
                observer!!.addOnWindowVisibilityChangeListener(listener)
                if (action == null && (view.windowVisibility == android.view.View.VISIBLE) == visible) ready.complete(Unit)
            }
            // O framework pode emitir GONE dentro da ação; registrar depois perde esse callback.
            try { action?.invoke(); withTimeout(10_000) { ready.await() } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Janela do IME não mudou para $visible em $stage: ${imeState()}", error)
            } finally {
                instrumentation.runOnMainSync {
                    observer?.takeIf { it.isAlive }?.removeOnWindowVisibilityChangeListener(listener)
                }
            }
            if (visible && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // A janela pode estar visível antes de enviar seu primeiro desenho ao compositor.
                val drawn = kotlinx.coroutines.CompletableDeferred<Unit>()
                val frames = java.util.concurrent.atomic.AtomicInteger()
                instrumentation.runOnMainSync {
                    val view = checkNotNull(com.keyflow.ime.KeyFlowKeyboardService.activeViewForTest)
                    view.viewTreeObserver.registerFrameCommitCallback {
                        frames.incrementAndGet()
                        view.viewTreeObserver.registerFrameCommitCallback { frames.incrementAndGet(); drawn.complete(Unit) }
                        view.postInvalidateOnAnimation()
                    }
                    view.postInvalidateOnAnimation()
                }
                try { withTimeout(10_000) { drawn.await() } } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    var state = ""
                    instrumentation.runOnMainSync {
                        val view = checkNotNull(com.keyflow.ime.KeyFlowKeyboardService.activeViewForTest)
                        state = "window=${view.windowVisibility}, shown=${view.isShown}, attached=${view.isAttachedToWindow}, hardware=${view.isHardwareAccelerated}, size=${view.width}x${view.height}"
                    }
                    throw AssertionError("IME não desenhou em $stage: ${frames.get()} quadros, $state", error)
                }
            }
        }
        fun screenshot(name: String) {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "\nKEYFLOW_P3_IME_$name:${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}\n")
                })
            } finally { bitmap.recycle() }
        }
        userSettings.capture()
        try {
            val ime = "${context.packageName}/com.keyflow.ime.KeyFlowKeyboardService"
            device.executeShellCommand("ime enable $ime")
            device.executeShellCommand("ime set $ime")
            val intent = Intent().setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val probe = ActivityScenario.launch<com.keyflow.ui.ImeProbeActivity>(intent)
            try {
                var editorField: android.widget.EditText? = null
                var typeNullField: android.widget.EditText? = null
                probe.onActivity { activity ->
                    val field = activity.currentFocus
                    assertTrue("o campo real do probe precisa ter foco", field is android.widget.EditText)
                    assertEquals("ime_probe_field", field?.contentDescription)
                    editorField = field as android.widget.EditText
                    typeNullField = android.widget.EditText(activity).apply {
                        id = android.view.View.generateViewId()
                        inputType = android.text.InputType.TYPE_NULL
                    }
                    (editorField!!.parent as android.view.ViewGroup).addView(typeNullField, 1, 1)
                    assertTrue(editorField!!.requestFocus())
                }
                awaitKeyboard(true, "abertura inicial", 15_000)
                awaitWindow(true, "abertura inicial")
                screenshot("VISIVEL")
                for (failClose in listOf(false, true)) {
                    OverlayService.visionSessionCloseForTest = if (failClose) {
                        { throw SecurityException("falha ao fechar projeção") }
                    } else null
                    awaitWindow(false, "ocultação $failClose") {
                        instrumentation.runOnMainSync { service.hideVisionViews(emptyList()) }
                    }
                    awaitKeyboard(false, "ocultação $failClose")
                    if (!failClose) screenshot("OCULTO")
                    awaitWindow(true, "restauração da limpeza $failClose") {
                        instrumentation.runOnMainSync { service.cleanupVision() }
                    }
                    awaitKeyboard(true, "restauração da limpeza $failClose")
                }
                OverlayService.visionSessionCloseForTest = null
                awaitWindow(false, "ocultação antes de TYPE_NULL") {
                    instrumentation.runOnMainSync { service.hideVisionViews(emptyList()) }
                }
                awaitKeyboard(false, "ocultação antes de TYPE_NULL")
                probe.onActivity { assertTrue(checkNotNull(typeNullField).requestFocus()) }
                instrumentation.waitForIdleSync()
                assertTrue("janela transitória deve abrir sessão TYPE_NULL", com.keyflow.ime.KeyFlowKeyboardService.isRawInputFieldForTest)
                instrumentation.runOnMainSync { service.cleanupVision() }
                assertEquals(false, KeyFlowController.keyboardVisible.value)
                awaitWindow(true, "restauração após TYPE_NULL") {
                    probe.onActivity { assertTrue(checkNotNull(editorField).requestFocus()) }
                }
                awaitKeyboard(true, "restauração após TYPE_NULL")
                awaitWindow(false, "consentimento") {
                    OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_VISION))
                }
                val deny = device.wait(Until.findObject(By.text(Pattern.compile("(?i)cancel|cancelar"))), 15_000)
                assertNotNull("consentimento não apareceu com o editor aberto", deny)
                awaitKeyboard(false, "consentimento")
                awaitWindow(true, "restauração após negação") { deny!!.click() }
                withTimeout(15_000) { OverlayService.visionFlowStateForTest.first { it == VisionFlowState.NONE } }
                awaitKeyboard(true, "restauração após negação", 15_000)
                screenshot("RESTAURADO")
                assertEquals(ButtonState.PAUSED, KeyFlowController.buttonState.value)
                awaitWindow(false, "ocultação por voltar") { device.pressBack() }
                awaitKeyboard(false, "ocultação por voltar")
                instrumentation.runOnMainSync {
                    val restore = KeyFlowController.hideKeyboardForCapture()
                    try { assertNull("captura não deve reabrir um teclado antes oculto", restore) }
                    finally { restore?.invoke() }
                    service.hideVisionViews(emptyList())
                    service.cleanupVision()
                }
                assertEquals(false, KeyFlowController.keyboardVisible.value)
            } finally {
                OverlayService.visionSessionCloseForTest = null
                instrumentation.runOnMainSync { service.cleanupVision() }
                probe.close()
            }
        } finally { userSettings.restore() }
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
                OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_CANCEL))
                withTimeout(15_000) { OverlayService.visionFlowStateForTest.first { it == VisionFlowState.NONE } }
            }
            if (KeyFlowController.buttonState.value in listOf(ButtonState.PAUSED, ButtonState.RECORDING)) {
                OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_CANCEL))
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
            val voiceAction = if (mode == RecordingMode.TOGGLE) OverlayService.ACTION_RECORD else OverlayService.ACTION_CANCEL
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
                OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_CANCEL))
                withTimeout(15_000) { OverlayService.visionFlowStateForTest.first { it == VisionFlowState.NONE } }
            }
            if (KeyFlowController.buttonState.value in listOf(ButtonState.PAUSED, ButtonState.RECORDING)) {
                OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_CANCEL))
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
