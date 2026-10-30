package com.keyflow.ime

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.data.SettingsRepository
import com.keyflow.orbe.AcaoDaOrbeDoTeclado
import com.keyflow.orbe.ConfigOrbeTeclado
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regressão do antigo seletor: os três cenários agora passam pela orbe, sem chips (KBO-05). */
@RunWith(AndroidJUnit4::class)
class KeyFlowKeyboardSelectorTest {
    @get:Rule val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentacao.targetContext
    private val device get() = UiDevice.getInstance(instrumentacao)
    private val guarda = UserSettingsGuard(context)
    private lateinit var original: ConfigOrbeTeclado
    private val view get() = KeyFlowKeyboardService.activeViewForTest!!
    private val repo get() = SettingsRepository(context)

    @Before fun preparar() {
        guarda.capture()
        guarda.forceExpanded()
        runBlocking {
            original = repo.configOrbeTecladoOnce()
            repo.atualizarOrbeTeclado { ConfigOrbeTeclado(copiarDaOrbePrincipal = false) }
            repo.saveDisclosureConsent(DISCLOSURE_VERSION, declined = false)
        }
        OverlayService.cancelWindowMsForTest = 30_000
        shell("ime enable ${context.packageName}/com.keyflow.ime.KeyFlowKeyboardService")
        shell("ime set ${context.packageName}/com.keyflow.ime.KeyFlowKeyboardService")
        val intent = Intent().setClassName(context.packageName, "com.keyflow.ui.ImeProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        awaitKeyboardAttached(device) { context.startActivity(intent) }
        esperar("configuração própria não chegou ao teclado") { !view.configOrbeForTest.copiarDaOrbePrincipal && view.circuloDaOrbe != null }
    }

    @After fun restaurar() {
        OverlayService.stop(context)
        OverlayService.cancelWindowMsForTest = null
        if (::original.isInitialized) runBlocking { repo.atualizarOrbeTeclado { original } }
        guarda.restore()
        device.pressHome()
        KeyFlowController.clearInserter()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
    }

    @Test fun segurarGravandoNaoAbreChipsNemMoveAOrbe() {
        gravar()
        val antes = view.circuloDaOrbe
        val (x, y) = centro()
        val inicio = SystemClock.uptimeMillis()
        injetar(MotionEvent.ACTION_DOWN, x, y, inicio)
        esperar("segurar não entrou no modo de arrasto") { view.arrastoDaOrbeForTest }
        assertTrue(view.barViewsForTest.none { it.selectorOpenForTest })
        assertEquals(antes, view.circuloDaOrbe)
        injetar(MotionEvent.ACTION_UP, x, y, inicio)
        assertEquals(ButtonState.RECORDING, KeyFlowController.buttonState.value)
    }

    @Test fun arrastarParaCentroCancelaEToqueConfiguradoDesfaz() {
        gravar()
        val (x, y) = centro()
        val inicio = SystemClock.uptimeMillis()
        injetar(MotionEvent.ACTION_DOWN, x, y, inicio)
        esperar("segurar não entrou no modo de arrasto") { view.arrastoDaOrbeForTest }
        val sinal = if (x < device.displayWidth / 2) 1 else -1
        val destino = x + sinal * view.circuloDaOrbe!!.diametro
        injetar(MotionEvent.ACTION_MOVE, destino, y, inicio)
        injetar(MotionEvent.ACTION_UP, destino, y, inicio)
        esperar("arrasto lateral não cancelou") { KeyFlowController.buttonState.value == ButtonState.CANCELLING }
        configurarToque(AcaoDaOrbeDoTeclado.CancelarDesfazer)
        tocar()
        esperar("ação configurada não desfez o cancelamento") { KeyFlowController.buttonState.value == ButtonState.PAUSED }
        assertTrue(view.barViewsForTest.none { it.selectorOpenForTest })
    }

    @Test fun configuracaoAoVivoPausaERetomaSemRecriarTeclado() {
        gravar()
        val antes = view
        configurarToque(AcaoDaOrbeDoTeclado.PausarRetomar)
        assertSame(antes, view)
        tocar()
        esperar("toque remapeado não pausou") { KeyFlowController.buttonState.value == ButtonState.PAUSED }
        tocar()
        esperar("toque remapeado não retomou") { KeyFlowController.buttonState.value == ButtonState.RECORDING }
    }

    private fun configurarToque(acao: AcaoDaOrbeDoTeclado) {
        runBlocking { repo.atualizarOrbeTeclado { it.copy(toque1 = acao) } }
        esperar("ação não chegou ao IME") { view.configOrbeForTest.toque1 == acao }
    }
    private fun gravar() {
        tocar()
        esperar("toque não iniciou gravação") { KeyFlowController.buttonState.value == ButtonState.RECORDING && view.micLabelForTest?.startsWith("●") == true }
    }
    private fun tocar() {
        val (x, y) = centro()
        val inicio = SystemClock.uptimeMillis()
        injetar(MotionEvent.ACTION_DOWN, x, y, inicio)
        injetar(MotionEvent.ACTION_UP, x, y, inicio)
    }
    private fun centro(): Pair<Int, Int> {
        var ponto = 0 to 0
        instrumentacao.runOnMainSync {
            val posicao = IntArray(2).also { view.getLocationOnScreen(it) }
            val circulo = view.circuloDaOrbe!!
            ponto = posicao[0] + circulo.centroX to posicao[1] + circulo.centroY
        }
        return ponto
    }
    private fun injetar(acao: Int, x: Int, y: Int, inicio: Long) {
        val evento = MotionEvent.obtain(inicio, SystemClock.uptimeMillis(), acao, x.toFloat(), y.toFloat(), 0)
        evento.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(instrumentacao.uiAutomation.injectInputEvent(evento, true)) } finally { evento.recycle() }
    }
    private fun esperar(mensagem: String, condicao: () -> Boolean) {
        val fim = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < fim) {
            if (condicao()) return
            Thread.sleep(50)
        }
        throw AssertionError(mensagem)
    }
    private fun shell(comando: String) {
        instrumentacao.uiAutomation.executeShellCommand(comando).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }
}
