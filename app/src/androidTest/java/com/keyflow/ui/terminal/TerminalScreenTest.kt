package com.keyflow.ui.terminal

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.Host
import com.keyflow.computador.app.ComputadorApp
import com.keyflow.computador.app.DependenciasFalsas
import com.keyflow.computador.app.TerminalActivity
import com.keyflow.computador.sessao.DonoDaSessao
import com.keyflow.computador.sessao.EstadoSessao
import com.keyflow.computador.sessao.ResultadoAbertura
import com.keyflow.computador.sessao.SessaoResumo
import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** CMP-24, 25, 28, 29, 30, 31: a tela com o emulador real (a ligação vem do [ComputadorApp] com conexão falsa). */
@RunWith(AndroidJUnit4::class)
class TerminalScreenTest {
    @get:Rule val regra = createComposeRule()
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = instr.targetContext
    private val host = Host(id = "h1", nome = "dev-server", endereco = "dev.example.org", usuario = "yan", autenticacao = AutenticacaoHost.Senha)

    private fun resumo(id: String, dono: DonoDaSessao = DonoDaSessao.USUARIO, estado: EstadoSessao = EstadoSessao.Aberta) = SessaoResumo(id, "h1", dono, false, estado)

    private fun esperar(ms: Long = 10_000, condicao: () -> Boolean) {
        val fim = System.currentTimeMillis() + ms
        while (!condicao()) {
            if (System.currentTimeMillis() > fim) throw AssertionError("tempo esgotado")
            instr.waitForIdleSync(); Thread.sleep(25)
        }
    }

    @Test fun abasTemRotulosAlvosGrandesEFecharChamaOCallback() {
        val fechadas = mutableListOf<String>()
        regra.setContent {
            KeyFlowTheme(dark = false) {
                TerminalScreen(
                    sessoes = listOf(resumo("a"), resumo("b", DonoDaSessao.AGENTE)), hosts = listOf(host), ativaId = "a",
                    ligacaoDe = { null }, aviso = null, onSelecionar = {}, onFecharAba = { fechadas += it }, onNovaSessao = {}, onAbrirLink = {}, onCopiar = {}, lerAreaDeTransferencia = { null },
                )
            }
        }
        regra.onNodeWithTag(abaTag("a")).assertIsDisplayed()
        regra.onNode(hasContentDescription("Terminal 1")).assertIsDisplayed()
        regra.onNode(hasContentDescription("Agente 2 ●")).assertIsDisplayed()
        val alvo = regra.onNodeWithTag(abaFecharTag("a")).fetchSemanticsNode().size
        val dp = ctx.resources.displayMetrics.density
        assertTrue("alvo de fechar >= 48dp", alvo.width >= 48 * dp - 1 && alvo.height >= 48 * dp - 1)
        val nova = regra.onNodeWithTag(TERMINAL_NOVA_ABA_TAG).fetchSemanticsNode().size
        assertTrue("alvo de + >= 48dp", nova.width >= 48 * dp - 1 && nova.height >= 48 * dp - 1)
        regra.onNodeWithTag(abaFecharTag("b")).performClick()
        assertEquals(listOf("b"), fechadas)
        regra.onNodeWithTag(TERMINAL_VAZIO_TAG).assertIsDisplayed()
    }

    @Test fun estadosTemSimboloEPalavraNaoSoCor() {
        val estados = listOf(
            EstadoSessao.Conectando to "Conectando…", EstadoSessao.Aberta to "Conectado", EstadoSessao.Reconectando(3) to "Reconectando… (tentativa 3)",
            EstadoSessao.Falhou(CausaSsh.RECUSADA) to "Caiu: O servidor recusou a conexão.", EstadoSessao.PrecisaDoUsuario to "Toque para reconectar",
        )
        for ((estado, palavra) in estados) {
            val texto = textoDoEstado(estado)
            assertTrue("$texto deve conter $palavra", texto.contains(palavra))
            assertTrue("$texto deve começar por um símbolo", texto.first() in "⟳●✕⚠○")
        }
    }

    @Test fun avisoDaNonaSessaoApareceNaTela() {
        regra.setContent {
            KeyFlowTheme(dark = true) {
                TerminalScreen(
                    sessoes = emptyList(), hosts = listOf(host), ativaId = null, ligacaoDe = { null }, aviso = "O limite é de 8 sessões abertas. Feche uma para abrir outra.",
                    onSelecionar = {}, onFecharAba = {}, onNovaSessao = {}, onAbrirLink = {}, onCopiar = {}, lerAreaDeTransferencia = { null },
                )
            }
        }
        regra.onNodeWithTag(TERMINAL_AVISO_TAG).assertIsDisplayed()
    }

}
