package com.keyflow.ui.terminal

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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

/** CMP-24, 29, 30: a `TerminalActivity` com o emulador real e conexão falsa (sessões do [ComputadorApp] de teste). */
@RunWith(AndroidJUnit4::class)
class TerminalActivityTest {
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

    @Test fun activityNaoPedeFlagSecureEVoltarNaoFechaAsSessoes() {
        val deps = DependenciasFalsas()
        val app = ComputadorApp.paraTeste(ctx, deps)
        val aberta = app.abrirSessao(host, DonoDaSessao.USUARIO, androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Black) as ResultadoAbertura.Aberta
        esperar { app.gerenciador.resumos.value.any { it.estado == EstadoSessao.Aberta } }
        ActivityScenario.launch<TerminalActivity>(TerminalActivity.intent(ctx, sessaoId = aberta.sessao.id).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { cenario ->
            cenario.onActivity { a -> assertEquals("D17: sem FLAG_SECURE", 0, a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
            cenario.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            instr.waitForIdleSync()
        }
        // Voltar saiu da tela; a sessão e o emulador continuam.
        assertTrue(app.gerenciador.resumos.value.any { it.id == aberta.sessao.id && it.estado == EstadoSessao.Aberta })
        assertTrue(app.emuladores.de(aberta.sessao.id) != null)
    }

    @Test fun fecharAAbaFechaASessaoENoveSessoesMostramOMotivo() {
        val deps = DependenciasFalsas()
        val app = ComputadorApp.paraTeste(ctx, deps)
        val cor = androidx.compose.ui.graphics.Color.White
        val sessoes = (1..8).map { (app.abrirSessao(host, DonoDaSessao.USUARIO, cor, cor) as ResultadoAbertura.Aberta).sessao }
        val nona = app.abrirSessao(host, DonoDaSessao.USUARIO, cor, cor)
        assertTrue(nona is ResultadoAbertura.Recusada)
        kotlinx.coroutines.runBlocking { app.gerenciador.fechar(sessoes.first().id) }
        assertTrue(app.gerenciador.resumos.value.none { it.id == sessoes.first().id })
        esperar { app.emuladores.de(sessoes.first().id) == null }
        kotlinx.coroutines.runBlocking { app.gerenciador.fecharTodas() }
    }

    @Test fun girarATelaEnviaWindowChangeComOTamanhoNovo() {
        val deps = DependenciasFalsas()
        val app = ComputadorApp.paraTeste(ctx, deps)
        val cor = androidx.compose.ui.graphics.Color.White
        val aberta = app.abrirSessao(host, DonoDaSessao.USUARIO, cor, cor) as ResultadoAbertura.Aberta
        esperar { deps.conexoes.firstOrNull()?.ptyInicial != null }
        ActivityScenario.launch<TerminalActivity>(TerminalActivity.intent(ctx, sessaoId = aberta.sessao.id).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { cenario ->
            cenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            esperar { deps.conexoes.first().tamanhos.isNotEmpty() }
            val retrato = deps.conexoes.first().tamanhos.last()
            deps.conexoes.first().tamanhos.clear()
            cenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            esperar { deps.conexoes.first().tamanhos.isNotEmpty() }
            val paisagem = deps.conexoes.first().tamanhos.last()
            assertTrue("colunas ${retrato.colunas} -> ${paisagem.colunas}", paisagem.colunas > retrato.colunas)
            assertNotEquals(retrato.linhas, paisagem.linhas)
            assertTrue("pixels vão junto", paisagem.largura > 0 && paisagem.altura > 0)
            cenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        kotlinx.coroutines.runBlocking { app.gerenciador.fecharTodas() }
    }

    @Test fun altScreenEscolhaDeTextoDoServidorChegaAoEmulador() {
        val deps = DependenciasFalsas()
        val app = ComputadorApp.paraTeste(ctx, deps)
        val cor = androidx.compose.ui.graphics.Color.White
        val aberta = app.abrirSessao(host, DonoDaSessao.USUARIO, cor, cor) as ResultadoAbertura.Aberta
        esperar { deps.conexoes.firstOrNull()?.ptyInicial != null }
        deps.conexoes.first().servidor("\u001b[?1049h\u001b[2J\u001b[Hhttps://tela.alternativa.test/ok\r\n".toByteArray())
        val emulador = app.emuladores.de(aberta.sessao.id)!!.emulador
        esperar { emulador.getUrls().any { it.url == "https://tela.alternativa.test/ok" } }
        kotlinx.coroutines.runBlocking { app.gerenciador.fecharTodas() }
    }
}
