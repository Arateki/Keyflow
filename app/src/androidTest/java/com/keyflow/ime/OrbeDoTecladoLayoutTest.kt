package com.keyflow.ime

import android.view.View
import android.view.WindowManager
import android.content.Intent
import android.os.SystemClock
import android.inputmethodservice.InputMethodService.Insets
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.keyflow.core.KeyFlowController
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.data.SettingsRepository
import com.keyflow.orbe.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import kotlin.math.roundToInt
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.ButtonState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Contrato inicial da faixa fixa; geometria e viewport real são conferidos na mesma classe. */
@RunWith(AndroidJUnit4::class)
class OrbeDoTecladoLayoutTest {
    private val instrumentacao = InstrumentationRegistry.getInstrumentation()

    private val context get() = instrumentacao.targetContext
    private val device get() = UiDevice.getInstance(instrumentacao)
    private val guarda = UserSettingsGuard(context)

    @Before fun preparar() {
        guarda.capture()
        guarda.forceExpanded()
        runBlocking { SettingsRepository(context).saveBarsConfig(BarsConfig.DEFAULT) }
    }
    @After fun restaurar() {
        device.unfreezeRotation()
        instrumentacao.runOnMainSync { KeyFlowKeyboardService.activeViewForTest?.definirFaixaParaTeste(null) }
        KeyFlowController.updateButtonState(ButtonState.IDLE_NO_FIELD)
        guarda.restore()
        device.pressHome()
    }

    @Test fun reservaFaixaMesmoSemGradeEConservaAlturaAoGravar() {
        instrumentacao.runOnMainSync {
            val view = KeyFlowKeyboardView(
                context = instrumentacao.targetContext,
                onKey = {}, onMic = {}, onHide = {}, frozenSeconds = 12,
            )
            val largura = instrumentacao.targetContext.resources.displayMetrics.widthPixels
            fun medir() {
                view.measure(View.MeasureSpec.makeMeasureSpec(largura, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            medir()
            val conteudo = view.getChildAt(view.childCount - 1)
            val faixa = (36 * view.resources.displayMetrics.density).roundToInt()
            assertEquals(faixa, conteudo.top - view.gridOverscanPx)
            val alturaOciosa = view.height
            view.updateMicState(ButtonState.RECORDING)
            medir()
            assertEquals(alturaOciosa, view.height)
            assertEquals(faixa, conteudo.top - view.gridOverscanPx)
        }
    }
    @Test fun previaMiniaturaNaoReservaFaixaEMostraTodasAsFileiras() {
        instrumentacao.runOnMainSync {
            fun alturaDe(reservar: Boolean): Int {
                val view = KeyFlowKeyboardView(context, {}, {}, onHide = {}, frozenSeconds = 12, scale = 0.62f, reservarFaixaDaOrbe = reservar)
                view.setExpanded(true)
                view.measure(View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                return view.measuredHeight.also { assertEquals(if (reservar) (36 * context.resources.displayMetrics.density).roundToInt() else 0, view.faixaDaOrbePx) }
            }
            val faixa = (36 * context.resources.displayMetrics.density).roundToInt()
            assertEquals(faixa, alturaDe(true) - alturaDe(false))
        }
    }

    @Test fun ociosaCabeNoEncaixeEGravandoNaoIntersectaTeclas() {
        device.setOrientationNatural()
        abrirHospedeiro(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, false)
        val view = KeyFlowKeyboardService.activeViewForTest!!
        instrumentacao.runOnMainSync {
            view.updateMicState(ButtonState.IDLE_READY)
            assertTrue(view.circuloDaOrbe!!.cabeEm(view.encaixeDaOrbe!!))
            val encaixe = view.encaixeDaOrbe!!
            view.updateMicState(ButtonState.RECORDING)
            val circulo = view.circuloDaOrbe!!
            assertTrue(circulo.topo < encaixe.topo)
            assertEquals(encaixe.fundo, circulo.fundo)
            val grade = view.canvasGridForTest!!
            val origem = android.graphics.Rect(0, 0, grade.width, grade.height)
            view.offsetDescendantRectToMyCoords(grade, origem)
            assertFalse(circulo.intersecta(Retangulo(origem.left, origem.top, origem.right, origem.bottom)))
            assertEquals(encaixe, view.encaixeDaOrbe)
            view.updateMicState(ButtonState.IDLE_READY)
        }
    }

    @Test fun encaixeUnicoNaBarraMaisAltaEPreservacaoDosMicsNoJson() {
        instrumentacao.runOnMainSync {
            val barras = BarsConfig(mirrored = true, collapsed = listOf(
                com.keyflow.core.shortcuts.BarDef(listOf(com.keyflow.core.shortcuts.BarButton.Mic, com.keyflow.core.shortcuts.BarButton.Mic)),
                com.keyflow.core.shortcuts.BarDef(listOf(com.keyflow.core.shortcuts.BarButton.ConfigBtn, com.keyflow.core.shortcuts.BarButton.Mic, com.keyflow.core.shortcuts.BarButton.Mic)),
            ), expanded = emptyList())
            val json = com.keyflow.core.shortcuts.ShortcutCodec.encodeBars(barras)
            val view = KeyFlowKeyboardView(context, {}, {}, onHide = {}, barsConfig = barras)
            view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val coluna = view.getChildAt(view.childCount - 1) as android.widget.LinearLayout
            val visiveis = view.barViewsForTest.filter { it.parent === coluna }
            assertEquals(1, visiveis.count { it.encaixeDaOrbe != null })
            val dona = visiveis.single { it.encaixeDaOrbe != null }
            assertSame(coluna.getChildAt(0), dona)
            assertEquals(dona.layoutForTest.buttons[1].left.toInt(), dona.encaixeDaOrbe!!.esquerda)
            assertEquals(json, com.keyflow.core.shortcuts.ShortcutCodec.encodeBars(barras))
            assertEquals(4, barras.collapsed.sumOf { it.buttons.count { b -> b == com.keyflow.core.shortcuts.BarButton.Mic } })
            val semMic = barras.copy(collapsed = listOf(com.keyflow.core.shortcuts.BarDef(listOf(com.keyflow.core.shortcuts.BarButton.ConfigBtn))))
            val semOrbe = KeyFlowKeyboardView(context, {}, {}, onHide = {}, barsConfig = semMic)
            assertEquals(0, semOrbe.faixaDaOrbePx)
            assertNull(semOrbe.circuloDaOrbe)
        }
    }

    @Test fun soltarPttAntesDaConfirmacaoDoMicrofoneConservaJanelaDeEnvio() {
        instrumentacao.runOnMainSync {
            var agora = 0L
            val comandos = mutableListOf<ComandoDaOrbeDoTeclado>()
            val orbe = OrbeDoTecladoView(View(context), false, 12, { agora }, { comandos += it }, {}, {})
            orbe.atualizarConfig(ConfigOrbeTeclado(copiarDaOrbePrincipal = false, modo = ModoDaOrbe.SEGURAR_PARA_FALAR),
                com.keyflow.data.Settings(vibrationIntensity = 0))
            orbe.atualizarEstado(ButtonState.IDLE_READY)
            val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 32f, 32f, 0)
            orbe.toque(down, Circulo(32, 32, 32))
            down.recycle()
            agora = 500
            val up = android.view.MotionEvent.obtain(0, 500, android.view.MotionEvent.ACTION_UP, 32f, 32f, 0)
            orbe.toque(up, Circulo(32, 32, 32))
            up.recycle()
            assertEquals(listOf(ComandoDaOrbeDoTeclado.IniciarGravacao, ComandoDaOrbeDoTeclado.AbrirJanelaEnvioPtt), comandos)
            orbe.desanexar()
        }
    }

    @Test fun regiaoAplicadaNaJanelaExcluiCantosEConservaConteudo() {
        val plano = PlanoDeInsets.calcular(0, 36, true, Circulo(32, 32, 32))
        val insets = Insets()
        aplicarPlanoDeInsets(insets, plano, 100, 200)
        assertEquals(Insets.TOUCHABLE_INSETS_REGION, insets.touchableInsets)
        assertEquals(36, insets.contentTopInsets)
        assertEquals(36, insets.visibleTopInsets)
        assertFalse(insets.touchableRegion.contains(1, 1))
        assertFalse(insets.touchableRegion.contains(63, 0))
        assertTrue(insets.touchableRegion.contains(32, 1))
        assertTrue(insets.touchableRegion.contains(1, 63))
        assertFalse(insets.touchableRegion.contains(-1, 32))
        aplicarPlanoDeInsets(insets, PlanoDeInsets.calcular(0, 36, true, gradeEmArrasto = true), 100, 200)
        assertTrue(insets.touchableRegion.contains(1, 1))
    }

    @Test fun resizeRetratoPreservaViewportDoAppComESemOverscan() = conferirViewport(false, false)
    @Test fun resizePaisagemPreservaViewportDoAppComESemOverscan() = conferirViewport(false, true)
    @Test fun panRetratoPreservaViewportDoAppComESemOverscan() = conferirViewport(true, false)
    @Test fun panPaisagemPreservaViewportDoAppComESemOverscan() = conferirViewport(true, true)

    private fun conferirViewport(pan: Boolean, paisagem: Boolean) {
        if (paisagem) device.setOrientationLeft() else device.setOrientationNatural()
        val modo = if (pan) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        for (overscan in listOf(false, true)) {
            abrirHospedeiro(modo, overscan)
            val view = KeyFlowKeyboardService.activeViewForTest!!
            assertEquals(overscan, view.gridOverscanPx > 0)
            instrumentacao.runOnMainSync { view.definirFaixaParaTeste(0) }
            val antes = viewportEstavel()
            instrumentacao.runOnMainSync { view.definirFaixaParaTeste(null) }
            val depois = viewportEstavel()
            assertEquals("viewport do app mudou (pan=$pan, paisagem=$paisagem, overscan=$overscan)", antes, depois)
            assertEquals("campo perdeu foco", "true", depois.last())
            assertTrue("campo focado coberto pelo teclado: $depois", depois[6].toInt() <= depois[3].toInt())
        }
    }

    private fun abrirHospedeiro(ajuste: Int, overscan: Boolean) {
        runBlocking {
            SettingsRepository(context).saveShortcutGrids(if (overscan) listOf(ShortcutGrid(id = "orbe-layout", name = "teste", rows = 2, cols = 2)) else emptyList())
        }
        shell("ime enable ${context.packageName}/com.keyflow.ime.KeyFlowKeyboardService")
        shell("ime set ${context.packageName}/com.keyflow.ime.KeyFlowKeyboardService")
        val intent = Intent().setClassName(context.packageName, "com.keyflow.ui.OrbeDoTecladoProbeActivity")
            .putExtra("ajuste", ajuste).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        awaitKeyboardAttached(device) { context.startActivity(intent) }
        esperar("orbe sem geometria") { KeyFlowKeyboardService.activeViewForTest?.let { it.isShown && it.circuloDaOrbe != null } == true }
        viewportEstavel()
    }

    private fun viewportEstavel(): List<String> {
        var anterior: String? = null
        var iguais = 0
        var resultado = emptyList<String>()
        esperar("viewport do hospedeiro não assentou") {
            val atual = device.findObject(By.descStartsWith("orbe_viewport:"))?.contentDescription
            if (atual != null && atual == anterior) iguais++ else iguais = 0
            anterior = atual
            resultado = atual?.split(":").orEmpty(); ultimaLeitura = atual.toString()
            iguais >= 3 && resultado.size == 8
        }
        return resultado
    }
    private var ultimaLeitura = ""
    private fun esperar(mensagem: String, condicao: () -> Boolean) {
        val fim = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < fim) {
            if (condicao()) return
            Thread.sleep(100)
        }
        throw AssertionError("$mensagem ${ultimaLeitura}")
    }
    private fun shell(comando: String) {
        instrumentacao.uiAutomation.executeShellCommand(comando).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

}
