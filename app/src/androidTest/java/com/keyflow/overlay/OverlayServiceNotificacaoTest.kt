package com.keyflow.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.data.SettingsRepository
import com.keyflow.notificacao.AcaoDaOrbe
import com.keyflow.notificacao.Botao
import com.keyflow.notificacao.ConfigNotificacao
import com.keyflow.notificacao.app.NotificacaoDaOrbe
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
 * NTF-T10: o serviço real recebe o clique da orbe e os dos botões da notificação (NTF-09..11, 14, 16, 38, 48, 49). Dirige o [OverlayService] pelas ações
 * (o mesmo caminho dos `PendingIntent`s da notificação) e observa o `KeyFlowController.buttonState`. O serviço é parado no `@After`.
 */
@RunWith(AndroidJUnit4::class)
class OverlayServiceNotificacaoTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val repo get() = SettingsRepository(context)
    private var savedVersion = 0
    private var savedDeclined = false

    @Before
    fun setUp() {
        OverlayService.bloqueadaParaTeste = null
        shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        OverlayService.cancelWindowMsForTest = 30_000L
        runBlocking {
            val s = repo.settingsFlow().first()
            savedVersion = s.disclosureAcceptedVersion
            savedDeclined = s.disclosureDeclined
            repo.saveDisclosureConsent(DISCLOSURE_VERSION, declined = false)
            repo.atualizarNotificacao { ConfigNotificacao() }
        }
        OverlayService.start(context, OverlayService.actionIntent(context, OverlayService.ACTION_RECORD))
        waitUntilState(ButtonState.RECORDING)
    }

    @After
    fun tearDown() {
        OverlayService.cancelWindowMsForTest = null
        OverlayService.bloqueadaParaTeste = null
        OverlayService.stop(context)
        runBlocking {
            repo.atualizarNotificacao { ConfigNotificacao() }
            repo.saveDisclosureConsent(savedVersion, savedDeclined)
        }
    }

    private fun toqueNaOrbe() = context.startService(OverlayService.actionIntent(context, OverlayService.ACTION_NOTIF_ORB_TAP))

    private fun botao(b: Botao) {
        val acao = when (b) {
            Botao.PAUSAR, Botao.RETOMAR -> OverlayService.ACTION_NOTIF_PAUSE_RESUME
            Botao.DESCARTAR, Botao.ENCERRAR -> OverlayService.ACTION_NOTIF_CANCEL
            Botao.DESFAZER -> OverlayService.ACTION_NOTIF_UNDO
        }
        context.startService(OverlayService.actionIntent(context, acao).putExtra(NotificacaoDaOrbe.EXTRA_BOTAO, b.name))
    }

    /**
     * Manda o botão até o estado chegar. O `KeyFlowController.buttonState` é global e o do teste anterior pode ficar nele até o serviço novo subir, então
     * o primeiro clique pode cair antes de o serviço estar pronto (o `ACTION_RECORD` do `setUp` ainda não foi tratado): repetir é idempotente para estes botões.
     */
    private fun botaoAte(b: Botao, vararg estados: ButtonState) {
        val fim = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < fim) {
            botao(b)
            val ate = System.currentTimeMillis() + 800
            while (System.currentTimeMillis() < ate) {
                if (KeyFlowController.buttonState.value in estados) return
                Thread.sleep(50)
            }
        }
        throw AssertionError("o botão $b não levou a ${estados.joinToString()}; estado atual: ${KeyFlowController.buttonState.value}")
    }

    private fun config(f: (ConfigNotificacao) -> ConfigNotificacao) = runBlocking { repo.atualizarNotificacao(f) }

    @Test
    fun umToqueNaOrbeEnviaAGravacao() {
        toqueNaOrbe()
        waitUntilState(ButtonState.SENDING, ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY, ButtonState.ERROR, ButtonState.PENDING_PASTE)
    }

    @Test
    fun pausarRetomarEBotaoAtrasadoIgnorado() {
        botaoAte(Botao.PAUSAR, ButtonState.PAUSED)
        botao(Botao.PAUSAR) // o botão era do estado gravando: ignorado
        Thread.sleep(600)
        assertEquals(ButtonState.PAUSED, KeyFlowController.buttonState.value)
        botaoAte(Botao.RETOMAR, ButtonState.RECORDING)
    }

    @Test
    fun descartarAbreAJanelaDeDesfazerEDesfazerVoltaAPausado() {
        botaoAte(Botao.DESCARTAR, ButtonState.CANCELLING)
        botaoAte(Botao.DESFAZER, ButtonState.PAUSED)
    }

    @Test
    fun configSemToqueDeGravacaoEAceitaENenhumToqueGrava() {
        config { it.copy(copiarDaOrbePrincipal = false, acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.Nenhuma, acao3Toques = AcaoDaOrbe.Nenhuma) }
        waitUntil({ "config não chegou ao serviço" }) { runBlocking { repo.configNotificacaoOnce().acao1Toque == AcaoDaOrbe.Nenhuma } }
        Thread.sleep(300)
        toqueNaOrbe()
        Thread.sleep(900)
        assertEquals("1 toque em Nenhuma não faz nada", ButtonState.RECORDING, KeyFlowController.buttonState.value)
    }

    @Test
    fun remapearGravarEnviarParaDoisToquesGravaPorEle() {
        config { it.copy(copiarDaOrbePrincipal = false, acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.GravarEnviar) }
        Thread.sleep(500)
        toqueNaOrbe(); toqueNaOrbe()
        waitUntilState(ButtonState.SENDING, ButtonState.IDLE_NO_FIELD, ButtonState.IDLE_READY, ButtonState.ERROR, ButtonState.PENDING_PASTE)
    }

    /**
     * O bloqueio é simulado pelo gancho `bloqueadaParaTeste` (no lugar do `KeyguardManager`): mexer no bloqueio do emulador deixava o SystemUI em ANR e a
     * prova virava `Assume` quando o AVD não travava. O portão em si é coberto, com relógio virtual, em `NotificacaoHostTest`; aqui vai a fiação do serviço.
     */
    @Test
    fun comATelaBloqueadaEAOpcaoDesligadaNemAOrbeNemOsBotoesAgem() {
        OverlayService.bloqueadaParaTeste = { true }
        toqueNaOrbe()
        botao(Botao.PAUSAR)
        botao(Botao.DESCARTAR)
        Thread.sleep(1200)
        assertEquals("bloqueada + opção desligada: nada age", ButtonState.RECORDING, KeyFlowController.buttonState.value)
        // ligada, na mesma tela bloqueada: o botão age
        config { it.copy(permitirComTelaBloqueada = true) }
        waitUntil({ "config não chegou" }) { runBlocking { repo.configNotificacaoOnce().permitirComTelaBloqueada } }
        Thread.sleep(300)
        botaoAte(Botao.PAUSAR, ButtonState.PAUSED)
    }

    private fun waitUntilState(vararg expected: ButtonState, timeoutMs: Long = 8000) =
        waitUntil({ "esperando ${expected.joinToString(" ou ")}, estado atual: ${KeyFlowController.buttonState.value}" }, timeoutMs) {
            KeyFlowController.buttonState.value in expected
        }

    private fun waitUntil(message: () -> String, timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message())
    }

    private fun shellOut(command: String): String {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return try { java.io.FileInputStream(fd.fileDescriptor).readBytes().toString(Charsets.UTF_8) } finally { fd.close() }
    }

    private fun shell(command: String) {
        val fd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        try { java.io.FileInputStream(fd.fileDescriptor).readBytes() } finally { fd.close() }
    }
}
