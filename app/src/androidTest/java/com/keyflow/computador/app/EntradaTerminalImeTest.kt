package com.keyflow.computador.app

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.Host
import com.keyflow.computador.sessao.DonoDaSessao
import com.keyflow.computador.sessao.EstadoSessao
import com.keyflow.computador.sessao.ResultadoAbertura
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CMP-27 e CMP-31 com o KeyFlow IME como teclado atual: o `InputConnection` real do campo do terminal recebe o que um teclado de tela
 * entrega, e o servidor (conexão falsa) vê **uma** vez cada tecla. Não substitui o toque no teclado de verdade (T21, manual).
 */
@RunWith(AndroidJUnit4::class)
class EntradaTerminalImeTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = instr.targetContext
    private val host = Host(id = "h1", nome = "dev-server", endereco = "dev.example.org", usuario = "yan", autenticacao = AutenticacaoHost.Senha)
    private val deps = DependenciasFalsas()
    private var imeAnterior: String? = null
    private val imeKeyFlow = "com.keyflow/.ime.KeyFlowKeyboardService"

    private fun shell(cmd: String): String = instr.uiAutomation.executeShellCommand(cmd).use { f -> java.io.FileInputStream(f.fileDescriptor).bufferedReader().readText() }

    @Before fun ativarOKeyFlowIme() {
        imeAnterior = shell("settings get secure default_input_method").trim().takeIf { it.isNotEmpty() && it != "null" }
        shell("ime enable $imeKeyFlow")
        shell("ime set $imeKeyFlow")
    }

    @After fun restaurarIme() { imeAnterior?.let { shell("ime set $it") } }

    private fun campo(v: View): View? {
        if (v.javaClass.simpleName == "ImeInputView") return v
        if (v is ViewGroup) repeat(v.childCount) { campo(v.getChildAt(it))?.let { c -> return c } }
        return null
    }

    private fun esperar(ms: Long = 10_000, c: () -> Boolean) {
        val fim = System.currentTimeMillis() + ms
        while (!c()) { if (System.currentTimeMillis() > fim) throw AssertionError("tempo esgotado"); instr.waitForIdleSync(); Thread.sleep(25) }
    }

    private fun hex(s: String) = s.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02x".format(it) }

    private fun escrito(): String = String(deps.conexoes.first().tudoQueFoiEscrito(), Charsets.UTF_8)

    private fun <T> comTerminal(bloco: (ComputadorApp, View) -> T): T {
        val app = ComputadorApp.paraTeste(ctx, deps)
        val s = (app.abrirSessao(host, DonoDaSessao.USUARIO, Color.White, Color.Black) as ResultadoAbertura.Aberta).sessao
        esperar { app.gerenciador.resumos.value.any { it.estado == EstadoSessao.Aberta } }
        val intent = TerminalActivity.intent(ctx, sessaoId = s.id).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<TerminalActivity>(intent).use { cenario ->
            var view: View? = null
            esperar { cenario.onActivity { a -> view = campo(a.window.decorView) }; view != null }
            return try { bloco(app, view!!) } finally { kotlinx.coroutines.runBlocking { app.gerenciador.fecharTodas() } }
        }
    }

    private fun conexao(view: View, aoCriar: (android.view.inputmethod.InputConnection) -> Unit) {
        instr.runOnMainSync {
            view.requestFocus()
            val ic = view.onCreateInputConnection(EditorInfo())
            assertNotNull(ic)
            aoCriar(ic!!)
        }
        instr.waitForIdleSync()
    }

    @Test fun digitarLsLaEEnterChegaExatamenteUmaVez() = comTerminal { _, view ->
        val enter = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        val soltar = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
        conexao(view) { ic -> ic.commitText("ls -la", 1) }
        // O termlib solta texto de vários caracteres numa thread própria, um instante depois; uma pessoa não aperta Enter no mesmo milissegundo.
        esperar { escrito() == "ls -la" }
        conexao(view) { ic ->
            // O mesmo evento por dois caminhos (o `aa` da T1): só um chega.
            ic.sendKeyEvent(enter); view.dispatchKeyEvent(enter)
            ic.sendKeyEvent(soltar); view.dispatchKeyEvent(soltar)
        }
        esperar { escrito().contains("\r") }
        Thread.sleep(300)
        assertEquals(hex("ls -la\r"), hex(escrito()))
    }

    @Test fun acentosChegamEmUtf8EBackspaceApagaUmCaractere() = comTerminal { _, view ->
        val apagar = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        conexao(view) { ic -> ic.commitText("ç", 1); ic.commitText("é", 1); ic.sendKeyEvent(apagar); view.dispatchKeyEvent(apagar) }
        esperar { escrito().contains("\u007f") }
        Thread.sleep(300)
        assertEquals(hex("çé\u007f"), hex(escrito()))
        assertTrue(deps.conexoes.first().tudoQueFoiEscrito().toList().windowed(1).isNotEmpty())
    }

    @Test fun ditadoChegaSemQuebraDeLinha() = comTerminal { _, view ->
        conexao(view) { ic -> ic.commitText("olá mundo", 1) }
        esperar { escrito().isNotEmpty() }
        Thread.sleep(300)
        assertEquals(hex("olá mundo"), hex(escrito()))
        assertTrue(!escrito().contains('\n') && !escrito().contains('\r'))
    }

    @Test fun ditadoComQuebraDeLinhaPedeConfirmacaoEPorTantoNaoExecuta() = comTerminal { app, view ->
        conexao(view) { ic -> ic.commitText("echo a\necho b", 1) }
        val ligacao = app.emuladores.de(app.gerenciador.resumos.value.first().id)!!
        esperar { ligacao.pedidoDeColar.value != null }
        assertEquals("", escrito())
        assertEquals(2, ligacao.pedidoDeColar.value!!.linhas)
        ligacao.cancelarColar()
        Thread.sleep(300)
        assertEquals("cancelar não envia nada", "", escrito())
    }

    @Test fun ctrlCPeloTecladoFisicoViraETX() = comTerminal { _, view ->
        val t = System.currentTimeMillis()
        val ctrlC = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        conexao(view) { _ -> view.dispatchKeyEvent(ctrlC) }
        esperar { escrito().isNotEmpty() }
        Thread.sleep(300)
        assertEquals(hex("\u0003"), hex(escrito()))
    }
}
