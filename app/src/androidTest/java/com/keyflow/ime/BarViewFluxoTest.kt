package com.keyflow.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.ime.bars.BarCallbacks
import com.keyflow.ime.bars.BarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O botão de fluxo da `BarView` (FLX-36, FLX-32): desenhado com o ícone de fluxo, tocar chama o callback com o id, o botão sem gatilho
 * válido fica desabilitado e não dispara, e o do fluxo em execução fica aceso. A `BarView` é montada e medida direto — o IME real, com
 * a barra no teclado, é coberto por `KeyFlowKeyboardBarsTest`.
 */
@RunWith(AndroidJUnit4::class)
class BarViewFluxoTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val disparos = mutableListOf<String>()

    private fun barra(botoes: List<BarButton>, validos: Map<String, String>): BarView {
        lateinit var view: BarView
        instrumentation.runOnMainSync {
            view = BarView(
                context = context,
                def = BarDef(botoes),
                dark = false,
                expandedMode = false,
                callbacks = BarCallbacks(onRunFlow = { disparos += it }),
                flowButtons = validos,
            )
            view.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        return view
    }

    private fun tocar(view: BarView, indice: Int) {
        val ret = view.buttonScreenRectForTest(indice)
        val loc = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(loc) }
        val x = (ret.centerX() - loc[0]).toFloat()
        val y = (ret.centerY() - loc[1]).toFloat()
        instrumentation.runOnMainSync {
            val t = SystemClock.uptimeMillis()
            view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            view.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
        }
    }

    private fun desenhar(view: BarView): Bitmap {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync { view.draw(Canvas(bmp)) }
        return bmp
    }

    private fun mesmo(a: Bitmap, b: Bitmap) = a.sameAs(b)

    @Test
    fun tappingAnEnabledFlowButtonCallsBackWithItsId() {
        val view = barra(listOf(BarButton.Mic, BarButton.RunFlow("a1b2c3")), mapOf("a1b2c3" to "f1"))
        val indice = view.layoutForTest.buttons.first { it.button is BarButton.RunFlow }.index
        tocar(view, indice)
        assertEquals(listOf("a1b2c3"), disparos)
    }

    @Test
    fun anOrphanOrInvalidFlowButtonIsDisabledAndDoesNotFire() {
        // Sem gatilho válido (órfão, ou o fluxo ficou incompleto): fora do mapa de habilitados.
        val view = barra(listOf(BarButton.Mic, BarButton.RunFlow("a1b2c3")), validos = emptyMap())
        val indice = view.layoutForTest.buttons.first { it.button is BarButton.RunFlow }.index
        tocar(view, indice)
        assertTrue(disparos.isEmpty())
        // E desenha diferente do habilitado (o ícone esmaecido).
        val habilitada = barra(listOf(BarButton.Mic, BarButton.RunFlow("a1b2c3")), mapOf("a1b2c3" to "f1"))
        assertFalse(mesmo(desenhar(view), desenhar(habilitada)))
    }

    @Test
    fun theButtonIsDrawnWithTheFlowIconNotLeftBlank() {
        val comBotao = barra(listOf(BarButton.Mic, BarButton.RunFlow("a1b2c3")), mapOf("a1b2c3" to "f1"))
        val semIcone = barra(listOf(BarButton.Mic, BarButton.Mic), emptyMap())
        assertFalse(mesmo(desenhar(comBotao), desenhar(semIcone)))
        val area = comBotao.layoutForTest.buttons.first { it.button is BarButton.RunFlow }
        val bmp = desenhar(comBotao)
        val x0 = area.left.toInt() + 4
        val x1 = (area.left + area.width).toInt() - 4
        val centro = (x0..x1).flatMap { x -> (0 until bmp.height).map { y -> bmp.getPixel(x, y) } }.toSet()
        assertTrue("o botão ficou de uma cor só (sem ícone)", centro.size > 3)
    }

    @Test
    fun theButtonOfTheRunningFlowIsLitAndTurnsOffWhenItEnds() {
        val view = barra(listOf(BarButton.Mic, BarButton.RunFlow("a1b2c3")), mapOf("a1b2c3" to "f1"))
        val parado = desenhar(view)
        instrumentation.runOnMainSync { view.updateFluxo(EstadoExecucao.Rodando("f1", 0, TipoEtapa.DITAR)) }
        val aceso = desenhar(view)
        assertFalse(mesmo(parado, aceso))
        // Outro fluxo rodando não acende este botão.
        instrumentation.runOnMainSync { view.updateFluxo(EstadoExecucao.Rodando("outro", 0, TipoEtapa.DITAR)) }
        assertTrue(mesmo(parado, desenhar(view)))
        instrumentation.runOnMainSync { view.updateFluxo(EstadoExecucao.Rodando("f1", 0, TipoEtapa.DITAR)) }
        instrumentation.runOnMainSync { view.updateFluxo(EstadoExecucao.Ocioso) }
        assertTrue(mesmo(parado, desenhar(view)))
    }
}
