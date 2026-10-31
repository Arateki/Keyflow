package com.keyflow.computador.app

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.computador.ssh.PtyInfo
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CMP-23, 25, 35, 36 com o emulador do termlib de verdade (JNI) e um destino que só anota. */
@RunWith(AndroidJUnit4::class)
class LigacaoEmuladorTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ligacoes = mutableListOf<LigacaoEmulador>()

    private class Anotador : DestinoDoEmulador {
        val bytes = CopyOnWriteArrayList<ByteArray>()
        val tamanhos = CopyOnWriteArrayList<PtyInfo>()
        override suspend fun escrever(bytes: ByteArray) { this.bytes += bytes }
        override suspend fun redimensionar(pty: PtyInfo) { tamanhos += pty }
        fun texto() = String(bytes.fold(ByteArray(0)) { a, b -> a + b }, Charsets.ISO_8859_1)
    }

    private fun gravacao(nome: String) = instr.context.assets.open("computador/gravacoes/$nome").use { it.readBytes() }

    private fun nova(destino: Anotador, colunas: Int = 80, linhas: Int = 24): LigacaoEmulador {
        var ligacao: LigacaoEmulador? = null
        instr.runOnMainSync { ligacao = LigacaoEmulador(escopo, Color.White, Color.Black, colunas, linhas).also { it.ligarAoDestino(destino) } }
        return ligacao!!.also { ligacoes += it }
    }

    private fun esperar(ms: Long = 10_000, condicao: () -> Boolean) {
        val fim = System.currentTimeMillis() + ms
        while (!condicao()) {
            if (System.currentTimeMillis() > fim) throw AssertionError("tempo esgotado")
            instr.waitForIdleSync()
            Thread.sleep(20)
        }
    }

    @After fun limpar() { instr.runOnMainSync { ligacoes.forEach { it.fechar() } }; escopo.cancel() }

    @Test fun textoUtf8ECoresDesenhamNoEstadoDoEmulador() {
        val d = Anotador()
        val l = nova(d)
        l.escreverNaTela(gravacao("texto-cores.bin"))
        // Pergunta ao estado do emulador (não à captura): a saída do último comando (OSC 133) é o texto desenhado.
        esperar { l.emulador.getLastCommandOutput() == "Olá, terminal!" }
    }

    @Test fun respostasPermitidasChegamAoServidorEAsProibidasNaoGeramByte() {
        val d = Anotador()
        val l = nova(d)
        l.escreverNaTela(gravacao("hostil.bin"))
        esperar { d.texto().contains("?1;2c") && d.texto().contains("0n") }
        instr.waitForIdleSync()
        Thread.sleep(300)
        val saiu = d.texto()
        // DA1, DSR e CPR respondidos; kitty a=q respondido.
        assertTrue(saiu, saiu.contains("\u001b[?1;2c"))
        assertTrue(saiu.contains("\u001b[0n"))
        assertTrue(Regex("\u001b\\[\\d+;\\d+R").containsMatchIn(saiu))
        // A resposta do kitty a=q só existe com imagens ligadas: a Fase 2 (política por host) a testa.
        // Nada de DCS (DECRQSS), de OSC 52 nem de relatório de título, e nenhuma resposta ao ENQ.
        assertFalse(saiu.contains("\u001bP"))
        assertFalse(saiu.contains("\u001b]52"))
        assertFalse(saiu.contains("\u001b]l"))
        assertFalse(saiu.contains("\u0005"))
        assertFalse(saiu.contains("[21t"))
        // O que o filtro de saída pegou é contado, nunca guardado.
        assertEquals(0, l.descartesDeSaida)
    }

    @Test fun osc52DeEscritaPedeConsentimentoENegarELimpa() {
        val d = Anotador()
        val l = nova(d)
        l.escreverNaTela("\u001b]52;c;aGVsbG8=\u0007".toByteArray())
        esperar { l.pedidoDeClipboard.value != null }
        assertEquals("hello", l.pedidoDeClipboard.value)
        assertNull("negar não devolve o texto", l.responderClipboard(false))
        assertNull(l.pedidoDeClipboard.value)
        l.escreverNaTela("\u001b]52;c;aGVsbG8=\u0007".toByteArray())
        esperar { l.pedidoDeClipboard.value != null }
        assertEquals("hello", l.responderClipboard(true))
    }

    @Test fun osc52DeLeituraNuncaPedeNemResponde() {
        val d = Anotador()
        val l = nova(d)
        l.escreverNaTela("\u001b]52;c;?\u0007\u001b]52;;?\u001b\\fim\r\n".toByteArray())
        instr.waitForIdleSync()
        Thread.sleep(300)
        assertNull(l.pedidoDeClipboard.value)
        assertEquals("", d.texto())
    }

    @Test fun tituloVirouRotuloSanitizado() {
        val d = Anotador()
        val l = nova(d)
        l.escreverNaTela("\u001b]0;build-box: ~/app\u0007".toByteArray())
        assertEquals("build-box: ~/app", l.titulo.value)
    }

    @Test fun mudarAAreaEnviaWindowChangeComColunasLinhasEPixels() {
        val d = Anotador()
        val l = nova(d)
        instr.runOnMainSync { l.emulador.resize(30, 100) }
        esperar { d.tamanhos.isNotEmpty() }
        val t = d.tamanhos.last()
        assertEquals(100, t.colunas)
        assertEquals(30, t.linhas)
        assertEquals(30, l.tamanhoAtual().linhas)
        assertEquals(100, l.tamanhoAtual().colunas)
    }

    @Test fun teclasSaemNaOrdemEmQueForamDigitadas() {
        val d = Anotador()
        val l = nova(d)
        val texto = "ls -la /tmp && echo ação"
        instr.runOnMainSync { texto.forEach { l.emulador.dispatchCharacter(0, it) } }
        esperar { d.texto().length >= texto.toByteArray().size }
        assertEquals(texto, String(d.bytes.fold(ByteArray(0)) { a, b -> a + b }, Charsets.UTF_8))
    }

    @Test fun colarPassaPeloEmuladorEMantemOTexto() {
        val d = Anotador()
        val l = nova(d)
        instr.runOnMainSync { l.colar("echo a\necho b") }
        esperar { d.texto().contains("echo b") }
        assertTrue(d.texto().contains("echo a"))
    }

    @Test fun oHistoricoDeTextoSobreviveAoFecharEAbrirATela() {
        val d = Anotador()
        val l = nova(d)
        val linhas = buildString { repeat(200) { append("linha ").append(it).append("\r\n") } }
        l.escreverNaTela(linhas.toByteArray())
        esperar { l.emulador.retainedScrollbackLines >= 170 }
        val antes = l.emulador.retainedScrollbackLines
        // `onTrimMemory` com a tela invisível não descarta o histórico de texto.
        val app = ComputadorApp.de(instr.targetContext)
        app.aoFaltarMemoria(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        instr.waitForIdleSync()
        assertEquals(antes, l.emulador.retainedScrollbackLines)
    }

    @Test fun fecharSoltaOEmuladorEIgnoraBytesTardios() {
        val d = Anotador()
        val l = nova(d)
        instr.runOnMainSync { l.fechar() }
        l.escreverNaTela("depois de fechar".toByteArray())
        assertEquals("", d.texto())
        val feito = CountDownLatch(1)
        instr.runOnMainSync { l.fechar(); feito.countDown() }
        assertTrue(feito.await(2, TimeUnit.SECONDS))
    }
}
