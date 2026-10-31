package com.keyflow.ime

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.keyflow.core.ButtonState
import com.keyflow.core.KeyFlowController
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.orbe.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KBO-09, KBO-13, KBO-14, KBO-18, KBO-25, KBO-36: os gestos da orbe do teclado no emulador. A view roda sozinha, com os toques
 * injetados como `MotionEvent`s e a janela da rajada encurtada pela config (`tapBurstWindowMs`); os comandos saem pelo mesmo
 * `onOrbeComando` que o serviço usa. O app de trás é o `OrbeDoTecladoProbeActivity`, no IME de verdade.
 */
@RunWith(AndroidJUnit4::class)
class OrbeDoTecladoGestureTest {
    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentacao.targetContext
    private val device get() = UiDevice.getInstance(instrumentacao)
    private val guarda = UserSettingsGuard(context)
    private val comandos = CopyOnWriteArrayList<ComandoDaOrbeDoTeclado>()
    private val janelaMs = 80

    @Before fun preparar() {
        guarda.capture()
        guarda.forceExpanded()
        runBlocking { SettingsRepository(context).saveBarsConfig(BarsConfig.DEFAULT) }
        comandos.clear()
    }

    @After fun restaurar() {
        device.unfreezeRotation()
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
        guarda.restore()
        device.pressHome()
    }

    private fun criarView(config: ConfigOrbeTeclado = ConfigOrbeTeclado(copiarDaOrbePrincipal = false)): KeyFlowKeyboardView {
        lateinit var view: KeyFlowKeyboardView
        instrumentacao.runOnMainSync {
            view = KeyFlowKeyboardView(context, onKey = {}, onMic = {}, onHide = {}, frozenSeconds = 12, onOrbeComando = { comandos += it })
            view.setExpanded(true)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            view.updateOrbeConfig(config, Settings(tapBurstWindowMs = janelaMs, vibrationIntensity = 0))
            view.updateMicState(ButtonState.IDLE_READY)
        }
        return view
    }

    private fun centro(view: KeyFlowKeyboardView): Pair<Float, Float> {
        var r = 0f to 0f
        instrumentacao.runOnMainSync { view.circuloDaOrbe!!.let { r = it.centroX.toFloat() to it.centroY.toFloat() } }
        return r
    }

    private fun evento(view: KeyFlowKeyboardView, acao: Int, x: Float, y: Float) = instrumentacao.runOnMainSync {
        val agora = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(agora, agora, acao, x, y, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun tocar(view: KeyFlowKeyboardView, vezes: Int) {
        val (x, y) = centro(view)
        repeat(vezes) {
            evento(view, MotionEvent.ACTION_DOWN, x, y)
            evento(view, MotionEvent.ACTION_UP, x, y)
        }
    }

    private fun esperar(mensagem: String, condicao: () -> Boolean) {
        val fim = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < fim) {
            if (condicao()) return
            instrumentacao.waitForIdleSync()
        }
        throw AssertionError(mensagem)
    }

    /** Nada mais chega depois da janela da rajada: dá três janelas ao relógio da própria view e olha de novo. */
    private fun assentar(view: KeyFlowKeyboardView) {
        val fim = SystemClock.uptimeMillis() + janelaMs * 3L
        while (SystemClock.uptimeMillis() < fim) instrumentacao.waitForIdleSync()
    }

    @Test fun umToqueNaFabricaIniciaEOSeguinteEnvia() {
        val view = criarView()
        tocar(view, 1)
        esperar("1 toque não iniciou a gravação") { comandos.isNotEmpty() }
        assertEquals(listOf(ComandoDaOrbeDoTeclado.IniciarGravacao), comandos.toList())
        instrumentacao.runOnMainSync { view.updateMicState(ButtonState.RECORDING) }
        tocar(view, 1)
        esperar("o toque seguinte não enviou") { comandos.size == 2 }
        assertEquals(ComandoDaOrbeDoTeclado.EnviarGravacao, comandos[1])
    }

    @Test fun doisToquesComRetrairExpandirDisparamSoEleENaoGravam() {
        val view = criarView()
        tocar(view, 2)
        esperar("2 toques não resolveram") { comandos.isNotEmpty() }
        assentar(view)
        assertEquals(listOf(ComandoDaOrbeDoTeclado.RetrairExpandirTeclado), comandos.toList())
    }

    @Test fun segurarNoAlternarNaoMudaAPosicaoDoEncaixe() {
        val view = criarView()
        val encaixe = view.encaixeDaOrbe
        val (x, y) = centro(view)
        evento(view, MotionEvent.ACTION_DOWN, x, y)
        esperar("segurar não entrou no modo-arrasto") { view.arrastoDaOrbeForTest }
        evento(view, MotionEvent.ACTION_UP, x, y)
        instrumentacao.runOnMainSync { view.measure(
            View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        ); view.layout(0, 0, view.measuredWidth, view.measuredHeight) }
        assertEquals(encaixe, view.encaixeDaOrbe)
    }

    @Test fun visaoAtivaEEncerradaPeloGravarEnviarEIgnoraOsOutrosToques() {
        val view = criarView()
        instrumentacao.runOnMainSync { view.updateVisionState(true) }
        tocar(view, 1)
        esperar("o toque não encerrou a Visão") { comandos.isNotEmpty() }
        assertEquals(listOf(ComandoDaOrbeDoTeclado.EncerrarVisao), comandos.toList())
        comandos.clear()
        tocar(view, 2)
        assentar(view)
        assertEquals(emptyList<ComandoDaOrbeDoTeclado>(), comandos.toList())
    }

    /** O IME de verdade: gravando, os cantos do retângulo da orbe fora do disco e fora do conteúdo chegam ao app de trás. */
    @Test fun cantosForaDoDiscoSaoRecebidosPeloAppDeTras() {
        device.setOrientationNatural()
        runBlocking { SettingsRepository(context).saveShortcutGrids(emptyList()) }
        shell("ime enable ${context.packageName}/com.keyflow.ime.KeyFlowKeyboardService")
        shell("ime set ${context.packageName}/com.keyflow.ime.KeyFlowKeyboardService")
        val intent = Intent().setClassName(context.packageName, "com.keyflow.ui.OrbeDoTecladoProbeActivity")
            .putExtra("ajuste", WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        awaitKeyboardAttached(device) { context.startActivity(intent) }
        esperar("orbe sem geometria") { KeyFlowKeyboardService.activeViewForTest?.let { it.isShown && it.circuloDaOrbe != null } == true }
        val view = KeyFlowKeyboardService.activeViewForTest!!
        instrumentacao.runOnMainSync { view.updateMicState(ButtonState.RECORDING) }
        val cantos = mutableListOf<Pair<Int, Int>>()
        instrumentacao.runOnMainSync {
            val c = view.circuloDaOrbe!!
            val origem = IntArray(2).also { view.getLocationOnScreen(it) }
            val grade = view.canvasGridForTest!!
            val g = android.graphics.Rect(0, 0, grade.width, grade.height).also { view.offsetDescendantRectToMyCoords(grade, it) }
            assertFalse("a orbe cobre a grade de teclas", c.intersecta(Retangulo(g.left, g.top, g.right, g.bottom)))
            val r = c.retanguloDelimitador
            for ((x, y) in listOf(r.esquerda + 2 to r.topo + 2, r.direita - 2 to r.topo + 2)) {
                val fora = (x - c.centroX).toLong().let { it * it } + (y - c.centroY).toLong().let { it * it } > c.raio.toLong() * c.raio
                if (fora && y < minOf(g.top, view.encaixeDaOrbe!!.topo)) cantos += origem[0] + x to origem[1] + y
            }
        }
        assertTrue("sem canto fora do disco e fora do conteúdo", cantos.isNotEmpty())
        var anterior = lerToques()
        for ((x, y) in cantos) {
            // A região tocável chega ao sistema depois do layout; repete o toque até o app de trás o ver.
            esperar("o app de trás não recebeu o toque em ($x, $y)") {
                device.click(x, y)
                val atual = lerToques()
                (atual.first > anterior.first && atual.second == x && atual.third == y).also { if (it) anterior = atual }
            }
        }
        assertTrue("a orbe iniciou algo ao tocar nos cantos", comandos.isEmpty())
    }

    private fun lerToques(): Triple<Int, Int, Int> {
        val d = device.findObject(By.descStartsWith("orbe_toques:"))?.contentDescription ?: return Triple(0, 0, 0)
        val p = d.split(":")
        return Triple(p[1].toInt(), p[2].toInt(), p[3].toInt())
    }

    private fun shell(comando: String) {
        instrumentacao.uiAutomation.executeShellCommand(comando).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }
}
