package com.keyflow.overlay

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode
import com.keyflow.notificacao.BotoesDaNotificacao
import com.keyflow.orbe.ArrastosDaOrbe
import com.keyflow.orbe.EfeitoDeArrasto
import com.keyflow.orbe.Interacao
import com.keyflow.ui.orb.OrbDragKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NTF-T10 (design §3.1): para cada estado, arrasta a `OrbButtonView` **real** nas três direções e o efeito bate com [ArrastosDaOrbe]. Um arrasto novo ou
 * alterado sem a linha na tabela falha aqui — e `BotoesDaNotificacaoTest` (JVM) garante que todo efeito da tabela (menos ABRIR_APP) vira botão.
 */
@RunWith(AndroidJUnit4::class)
class ArrastosDaOrbeConformidadeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun view(estado: ButtonState): OrbButtonView {
        val controller = RecordingGestureController(mode = { RecordingMode.TOGGLE }, currentState = { estado })
        return OrbButtonView(
            context = context, controller = controller, mode = { RecordingMode.TOGGLE },
            onIntent = {}, onPositionChanged = {}, onShortcut = {}, onOpenConfig = {},
        ).also { it.render(estado) }
    }

    private fun ev(action: Int, x: Float, y: Float, t: Long) = MotionEvent.obtain(0, t, action, x, y, 0)

    private fun espera(ms: Long, cond: () -> Boolean): Boolean {
        val fim = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fim) { if (cond()) return true; Thread.sleep(30) }
        return cond()
    }

    private fun efeitoDe(k: OrbDragKind?): EfeitoDeArrasto? = when (k) {
        OrbDragKind.PAUSE -> EfeitoDeArrasto.PAUSAR
        OrbDragKind.RESUME -> EfeitoDeArrasto.RETOMAR
        OrbDragKind.CANCEL -> EfeitoDeArrasto.DESCARTAR
        OrbDragKind.CONFIG -> EfeitoDeArrasto.ABRIR_APP
        null -> null
    }

    @Test
    fun cadaArrastoDaOrbeRealBateComATabela() {
        val direcoes = mapOf(
            Interacao.ARRASTAR_CIMA to (0f to -DIST),
            Interacao.ARRASTAR_LADO to (-DIST to 0f),
            Interacao.ARRASTAR_BAIXO to (0f to DIST),
        )
        for (estado in ButtonState.entries) {
            val tabela = ArrastosDaOrbe.comEfeito(estado)
            for ((direcao, d) in direcoes) {
                val v = view(estado)
                v.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 100f, 0))
                val travou = espera(1200) { v.dragLockedForTest }
                var efeito: EfeitoDeArrasto? = null
                if (travou) {
                    v.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 100f + d.first, 100f + d.second, 30))
                    espera(500) { v.dragKindForTest != null }
                    efeito = efeitoDe(v.dragKindForTest)
                }
                v.onTouchEvent(ev(MotionEvent.ACTION_CANCEL, 100f, 100f, 90))
                assertEquals("$estado / $direcao: a orbe real e a tabela divergem", tabela[direcao], efeito)
            }
            // e o que a tabela promete (menos ABRIR_APP) vira botão
            for (efeito in tabela.values) if (efeito != EfeitoDeArrasto.ABRIR_APP) {
                assertEquals(true, BotoesDaNotificacao.para(estado, false).todos.contains(BotoesDaNotificacao.botaoDe(efeito)))
            }
        }
    }

    private companion object { const val DIST = 160f }
}
