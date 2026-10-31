package com.keyflow.computador.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** O oráculo compila o C original DURANTE os unitários do pool: não simula o parser em Kotlin. */
class ParserLibvtermTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    private data class Observado(val proibidos: Int, val modo: Boolean, val estado: Int, val texto: String)

    private fun analisar(fluxos: List<ByteArray>, oitoBits: Boolean = false): List<Observado> {
        val out = temporarios.newFile()
        val erro = temporarios.newFile()
        val cmd = mutableListOf(executavel().absolutePath).apply { if (oitoBits) add("8bit") }
        val p = ProcessBuilder(cmd).redirectOutput(out).redirectError(erro).start()
        p.outputStream.bufferedWriter().use { w -> fluxos.forEach { w.write(hex(it)); w.newLine() } }
        assertTrue("oráculo excedeu 20 s", p.waitFor(20, TimeUnit.SECONDS))
        assertEquals(erro.readText(), 0, p.exitValue())
        return out.readLines().map {
            val c = it.split(' ', limit = 4)
            Observado(c[0].toInt(), c[1] == "1", c[2].toInt(), c.getOrElse(3) { "" })
        }.also { assertEquals(fluxos.size, it.size) }
    }

    @Test fun oraculoReconheceOsAtaquesDaRevisao() {
        val ataques = listOf("\u0005", "\u001b G", "\u001b[021t", "\u001b\u00ff G", "\u001b\u00ff[021t", "\u001b]021;?\u0007", "\u001b]052;${"c".repeat(40)};?\u001b\\", "\u001bP\u0000\$qm\u001b\\")
        val brutos = analisar(ataques.map(::bytes))
        assertEquals(listOf(1, 2, 4, 2, 4, 8, 16, 32), brutos.map { it.proibidos })
        val filtrados = analisar(ataques.map { FiltroDaSaidaDoServidor().processar(bytes(it)) })
        assertTrue(filtrados.all { it.proibidos == 0 && it.estado == 0 })
    }

    @Test fun escapeDentroDeStringNaoPrendeOLibvtermDepoisDaRemocao() {
        val fluxos = listOf("\u001b]0;velho\u001b[21tMARCADOR", "\u001bPzvelho\u001b]52;c;?\u0007MARCADOR")
        val original = analisar(fluxos.map(::bytes))
        val filtrado = analisar(fluxos.map { FiltroDaSaidaDoServidor().processar(bytes(it)) })
        original.zip(filtrado).forEach { (a, b) ->
            assertEquals(0, b.proibidos)
            assertEquals(0, b.estado)
            assertTrue(a.texto.endsWith(hex(bytes("MARCADOR"))))
            assertTrue(b.texto.endsWith(hex(bytes("MARCADOR"))))
        }
    }

    @Test fun fuzzComparaFluxosFiltradosComOParserRealEConfereDecckm() {
        val r = Random(20261031)
        val vocabulario = listOf("\u001b\u00ff G", "\u001b\u00ff[021t", "texto", "\u001b[021t", "\u001b[20t", "\u001b]052;cccc;?\u0007", "\u001b]021;?\u001b\\", "\u001bP\u0000\$qm\u001b\\", "\u001b G", "\u001b[?01h", "\u001b[?1l", "\u001b[2\u00051t", "\u001b]00;nome\u001b\\", "\u0018", "\u001a", "\u007f", "\u0000", "\u001b", "\u001b]8;;https://a.b/\u001b\\", "\u001bP", "\u001b]52;", "\u0007")
        val modos = mutableListOf<Boolean>()
        val filtrados = List(500) {
            val original = buildString { repeat(r.nextInt(1, 15)) { append(vocabulario.random(r)) }; append("\u0018FIM") }
            val f = FiltroDaSaidaDoServidor()
            val out = ByteArrayOutputStream()
            val b = bytes(original)
            var i = 0
            while (i < b.size) { val fim = minOf(b.size, i+r.nextInt(1, 8)); out.write(f.processar(b.copyOfRange(i, fim))); i=fim }
            modos += f.modoAplicacaoDasSetas
            out.toByteArray()
        }
        analisar(filtrados).forEachIndexed { i, o ->
            assertEquals("fluxo $i: ${hex(filtrados[i])}", 0, o.proibidos)
            assertEquals("estado do fluxo $i", 0, o.estado)
            assertEquals("DECCKM do fluxo $i", modos[i], o.modo)
            assertTrue("texto posterior ao fluxo $i", o.texto.endsWith(hex(bytes("FIM"))))
        }
    }

    @Test fun bytesArbitrariosFiltradosNaoDeixamConsultaNemPrefixoAbertoNoParserReal() {
        val r = Random(96)
        val fim = bytes("FIM")
        val filtrados = List(300) {
            val b = ByteArray(r.nextInt(1, 300)) { (if (r.nextInt(3) == 0) 27 else r.nextInt(256)).toByte() }
            FiltroDaSaidaDoServidor().processar(b)+fim
        }
        analisar(filtrados).forEachIndexed { i, o ->
            assertEquals("consultas do fluxo $i", 0, o.proibidos)
            assertEquals("estado do fluxo $i", 0, o.estado)
            assertTrue("texto posterior do fluxo $i: ${hex(filtrados[i])}", o.texto.endsWith(hex(fim)))
        }
    }

    @Test fun respostasC1ProibidasNaoChegamAoParserDoServidor() {
        val ataques = listOf("\u0090\$qm\u009c", "\u009b021t", "\u009d052;c;?\u009c")
        assertEquals(listOf(32, 4, 16), analisar(ataques.map(::bytes), true).map { it.proibidos })
        assertTrue(analisar(ataques.map { FiltroDeRespostas().filtrar(bytes(it)) }, true).all { it.proibidos == 0 })
    }

    companion object {
        @get:ClassRule @JvmStatic val temporarios = TemporaryFolder()
        private var oraculo: File? = null
        @Synchronized private fun executavel(): File {
            oraculo?.let { return it }
            val lib = File("../third_party/termlib/src/main/cpp/libvterm").canonicalFile
            val exe = File(temporarios.root, "parser-libvterm")
            val log = File(temporarios.root, "compilacao.log")
            val fontes = lib.resolve("src").listFiles()!!.filter { it.extension == "c" }.map { it.path }
            val cmd = listOf("cc", "-std=gnu11", "-DVTERM_STATIC", "-I${lib.resolve("include")}", "-I${lib.resolve("src")}", "src/test/resources/computador/parser/oraculo.c") + fontes + listOf("-o", exe.path)
            val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start()
            assertTrue("compilação do oráculo excedeu 30 s", p.waitFor(30, TimeUnit.SECONDS))
            assertEquals(log.readText(), 0, p.exitValue())
            oraculo = exe
            return exe
        }
    }
}
