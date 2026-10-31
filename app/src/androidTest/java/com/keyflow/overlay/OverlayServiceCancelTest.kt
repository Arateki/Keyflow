package com.keyflow.overlay

import android.Manifest
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.data.SettingsRepository
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentado de cancelar/desfazer (CANCEL-01..06). Dirige o [OverlayService] real pelo caminho
 * estável de ações/Intents (`ACTION_RECORD`/`ACTION_UNDO_CANCEL`/`ACTION_COMMIT_CANCEL`), não pelo
 * gesto físico (frágil) — espelhando a decisão G1. Observa o estado via [KeyFlowController.buttonState],
 * o mesmo `StateFlow` público que o `OverlayCoordinator.render()` atualiza a cada transição.
 */
@RunWith(AndroidJUnit4::class)
class OverlayServiceCancelTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private var savedDisclosureVersion = 0
    private var savedDisclosureDeclined = false

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        // Os testes desfazem/concluem/olham o cancelamento DENTRO da janela de desfazer: com os 3 s do produto, um processo
        // recém-criado ou um aparelho lento deixava a janela fechar antes (o "desfazer" achava IDLE; esteira de
        // instabilidade e emulador local, 2026-10-10). 30 s aqui; cancelExpiresAndDiscardsAudio, que testa a janela
        // fechando, volta aos 3 s.
        OverlayService.cancelWindowMsForTest = 30_000L
        // Sobe o serviço já em RECORDING: ACTION_RECORD chama ensureForeground() (startForeground
        // imediato, sem warm-up extra) e coordinator.onTap() (ocioso → grava, MIN_RECORDING_MS à parte).
        runBlocking {
            val s = SettingsRepository(context).settingsFlow().first()
            savedDisclosureVersion = s.disclosureAcceptedVersion
            savedDisclosureDeclined = s.disclosureDeclined
            SettingsRepository(context).saveDisclosureConsent(DISCLOSURE_VERSION, declined = false)
        }
        OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
        waitUntilState(ButtonState.RECORDING)
    }

    @After
    fun tearDown() {
        OverlayService.cancelWindowMsForTest = null
        OverlayService.stop(context)
        runBlocking {
            SettingsRepository(context).saveDisclosureConsent(savedDisclosureVersion, savedDisclosureDeclined)
        }
    }

    @Test
    fun cancelEntersCancellingAndShowsUndoBanner() {
        // CANCEL-01: grava/pausado + cancelar → CANCELLING (áudio retido, não descartado já).
        sendAction(OverlayService.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)

        // CANCEL-02: banner "Cancelado" com os botões Desfazer/Concluir visível na tela.
        assertTrue(
            "banner de desfazer não apareceu",
            device.wait(Until.hasObject(By.textContains("Desfazer")), TIMEOUT) != null,
        )
    }

    @Test
    fun undoCancelReturnsToPaused() {
        sendAction(OverlayService.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)

        // CANCEL-03: desfazer dentro da janela → volta a PAUSED com o áudio preservado.
        sendAction(OverlayService.ACTION_UNDO_CANCEL)
        waitUntilState(ButtonState.PAUSED)
    }

    @Test
    fun cancelExpiresAndDiscardsAudio() {
        OverlayService.cancelWindowMsForTest = null // a janela do produto, 3 s: é ela que este teste vê fechar
        sendAction(OverlayService.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)

        // CANCEL-04: sem ação dentro da janela (cancelWindowMs = 3000ms) → descarta e volta ao idle.
        waitUntilState(ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY, timeoutMs = 6000)
    }

    @Test
    fun commitCancelEndsImmediatelyWithoutWaitingWindow() {
        // Com a janela em 30 s (setUp), o "concluir" tem que encerrar muito antes dela. Com os 3 s do produto o teste dava 2 s
        // ao "concluir" — e no emulador lento só parar e soltar o gravador passou disso (esteira de instabilidade,
        // 2026-10-10). Assim "concluir funciona" (sai em segundos) e "concluir ignorado" (30 s em CANCELLING) não se confundem.
        sendAction(OverlayService.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)

        val start = System.currentTimeMillis()
        // CANCEL-05: toque na orbe durante CANCELLING conclui o descarte JÁ (sem esperar a janela).
        sendAction(OverlayService.ACTION_COMMIT_CANCEL)
        waitUntilState(ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY, timeoutMs = 15_000)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("commit deveria encerrar bem antes da janela de 30 s (foi ${elapsed}ms)", elapsed < 15_000)
    }

    @Test
    fun targetChangeDuringCancellingPreservesState() {
        sendAction(OverlayService.ACTION_CANCEL)
        waitUntilState(ButtonState.CANCELLING)

        // CANCEL-06: foco de campo muda durante CANCELLING → o estado PERMANECE CANCELLING (não
        // orfaniza o job de descarte) — lição já registrada no projeto.
        instrumentation.runOnMainSync { KeyFlowController.registerInserter { true } }
        assertEquals(ButtonState.CANCELLING, KeyFlowController.buttonState.value)
        instrumentation.runOnMainSync { KeyFlowController.clearInserter() }
        assertEquals(ButtonState.CANCELLING, KeyFlowController.buttonState.value)

        // O job de desfazer sobrevive (não orfanizou): ainda dá para desfazer normalmente.
        sendAction(OverlayService.ACTION_UNDO_CANCEL)
        waitUntilState(ButtonState.PAUSED)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun sendAction(action: String) {
        context.startService(OverlayService.actionIntent(context, action))
    }

    private fun waitUntilState(vararg expected: ButtonState, timeoutMs: Long = TIMEOUT) {
        // A mensagem é montada no fim da espera: antes ela dizia o estado do começo (e o array como endereço).
        waitUntil({ "esperando ${expected.joinToString(" ou ")}, estado atual: ${KeyFlowController.buttonState.value}" }, timeoutMs) {
            KeyFlowController.buttonState.value in expected
        }
    }

    private fun waitUntil(message: () -> String, timeoutMs: Long = TIMEOUT, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message())
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
        const val TIMEOUT = 5000L
    }
}
