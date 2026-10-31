package com.keyflow.fluxos.app

import android.Manifest
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.fluxos.execucao.DestinoFluxo
import com.keyflow.fluxos.execucao.ResultadoDitado
import com.keyflow.overlay.GestureIntent
import com.keyflow.overlay.OverlayService
import com.keyflow.overlay.TouchSignal
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import java.util.Collections
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O gancho do ditado de fluxo no serviço da orbe real (FLX-18, design §7.1): com o Push-to-talk configurado, o toque na orbe passa a
 * enviar a gravação que o fluxo abriu; fora de repouso o início é recusado; cancelar de vez devolve `Cancelado` ao fluxo. Dirige o
 * serviço pelo mesmo caminho dos demais testes do serviço (intents e o coordenador), sem gesto físico (frágil — decisão G1).
 */
@RunWith(AndroidJUnit4::class)
class FluxoDitadoTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var saved: Settings
    private lateinit var service: OverlayService
    private val resultados = Collections.synchronizedList(mutableListOf<ResultadoDitado>())
    private var inserido = 0

    @Before
    fun setUp() = runBlocking {
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        OverlayService.cancelWindowMsForTest = 30_000L
        val repository = SettingsRepository(context)
        saved = repository.settingsFlow().first()
        repository.saveSettings(saved.copy(overlayRecordingMode = RecordingMode.PUSH_TO_TALK))
        repository.saveDisclosureConsent(DISCLOSURE_VERSION, declined = false)
        OverlayService.stop(context)
        OverlayService.start(context, OverlayService.startOverlayIntent(context))
        waitUntil("o serviço da orbe não subiu") { OverlayService.isRunningForUi && OverlayService.instanceForVisionTest != null }
        service = checkNotNull(OverlayService.instanceForVisionTest)
        instrumentation.runOnMainSync { KeyFlowController.registerInserter { inserido++; true } }
        waitUntil("o repouso não chegou") { KeyFlowController.buttonState.value in REPOUSO }
    }

    @After
    fun tearDown() = runBlocking {
        instrumentation.runOnMainSync { KeyFlowController.clearInserter() }
        if (KeyFlowController.buttonState.value in listOf(ButtonState.RECORDING, ButtonState.PAUSED)) {
            context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_CANCEL))
            waitUntil("cancelamento não começou") { KeyFlowController.buttonState.value == ButtonState.CANCELLING }
        }
        if (KeyFlowController.buttonState.value == ButtonState.CANCELLING) {
            context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_COMMIT_CANCEL))
        }
        OverlayService.cancelWindowMsForTest = null
        OverlayService.stop(context)
        SettingsRepository(context).saveSettings(saved)
        Unit
    }

    private fun iniciar(): Boolean {
        var aceitou = false
        instrumentation.runOnMainSync { aceitou = service.ditarParaFluxo(DestinoFluxo(null) { resultados += it }) }
        return aceitou
    }

    @Test
    fun theOrbTapSendsTheFlowRecordingEvenWithPushToTalk() {
        // Fora do fluxo, em Push-to-talk, o toque classificado (TAP) não faz nada: a gravação é por pressão.
        var fora: GestureIntent? = null
        instrumentation.runOnMainSync { fora = service.gestureControllerForTest.onSignal(TouchSignal.TAP) }
        assertEquals(GestureIntent.NONE, fora)

        assertTrue("o ditado de fluxo não começou", iniciar())
        waitUntil("não chegou a RECORDING") { KeyFlowController.buttonState.value == ButtonState.RECORDING }
        // Durante o ditado do fluxo o modo é alternar (A5): o toque na orbe envia.
        var durante: GestureIntent? = null
        instrumentation.runOnMainSync { durante = service.gestureControllerForTest.onSignal(TouchSignal.TAP) }
        assertEquals(GestureIntent.STOP, durante)
        // Um segundo início, com a sessão em curso, é recusado.
        assertFalse(iniciar())

        Thread.sleep(900) // acima do mínimo de gravação (400 ms)
        instrumentation.runOnMainSync { service.coordinatorForTest.onIntent(durante!!) }
        waitUntil("o fluxo não recebeu o resultado do ditado", timeoutMs = 60_000) { resultados.size >= 1 }
        Thread.sleep(500)
        assertEquals("o destino devia ser avisado uma vez só", 1, resultados.size)
        assertEquals("o texto de uma sessão de fluxo não vai ao campo", 0, inserido)
        waitUntil("não voltou ao repouso") { KeyFlowController.buttonState.value in REPOUSO || KeyFlowController.buttonState.value == ButtonState.ERROR }
        // Acabou a sessão: o modo volta ao configurado.
        var depois: GestureIntent? = null
        instrumentation.runOnMainSync { depois = service.gestureControllerForTest.onSignal(TouchSignal.TAP) }
        assertEquals(GestureIntent.NONE, depois)
    }

    @Test
    fun startingOutsideIdleReturnsFalse() {
        context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
        waitUntil("a gravação comum não começou") { KeyFlowController.buttonState.value == ButtonState.RECORDING }
        assertFalse(iniciar())
        assertTrue(resultados.isEmpty())
        assertFalse(service.coordinatorForTest.ditadoDeFluxo)
    }

    @Test
    fun aFinishedCancellationReturnsCancelledToTheFlowOnce() {
        assertTrue(iniciar())
        waitUntil("não chegou a RECORDING") { KeyFlowController.buttonState.value == ButtonState.RECORDING }
        context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_CANCEL))
        waitUntil("o cancelamento não começou") { KeyFlowController.buttonState.value == ButtonState.CANCELLING }
        assertTrue("desfazer não libera o destino", resultados.isEmpty())
        context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_COMMIT_CANCEL))
        waitUntil("o cancelamento não concluiu") { resultados.size == 1 }
        Thread.sleep(500)
        assertEquals(listOf<ResultadoDitado>(ResultadoDitado.Cancelado), resultados.toList())
        assertEquals(0, inserido)
    }

    private fun shell(command: String) {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        try { java.io.FileInputStream(fd.fileDescriptor).readBytes() } finally { fd.close() }
    }

    private fun waitUntil(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("$message (estado ${KeyFlowController.buttonState.value})")
    }

    private companion object {
        val REPOUSO = setOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD)
    }
}
