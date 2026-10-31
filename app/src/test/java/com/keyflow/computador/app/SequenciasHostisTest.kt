package com.keyflow.computador.app

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CMP-35 com a gravação hostil: o que **chega** ao emulador (já filtrado) e o que **sai** dele rumo ao servidor.
 * As respostas do emulador real a estas consultas são conferidas no instrumentado (`LigacaoEmuladorTest`); aqui as respostas são escritas à mão,
 * pelo que um emulador **poderia** devolver, para provar que a lista de permitidos as separa.
 */
class SequenciasHostisTest {
    private val ESC = "\u001b"
    private val BEL = "\u0007"
    private val ST = "\u001b\\"

    private fun entrada(bytes: ByteArray): ByteArray = FiltroDaSaidaDoServidor().processar(bytes)
    private fun saida(texto: String): ByteArray = FiltroDeRespostas().filtrar(texto.toByteArray(Charsets.ISO_8859_1))

    @Test fun oArquivoEAConcatenacaoDosTrechos() {
        val arquivo = File("src/test/resources/computador/gravacoes/hostil.bin").readBytes()
        assertArrayEquals(Hostil.tudo(), arquivo)
    }

    @Test fun oQueChegaAoEmuladorNaoTemOQueNaoPodeSerRespondido() {
        val chegou = entrada(Hostil.tudo())
        assertFalse("ENQ", chegou.contains(0x05))
        assertFalse("CSI 21 t", chegou.contem("${ESC}[21t"))
        assertFalse("CSI 20 t", chegou.contem("${ESC}[20t"))
        assertFalse("OSC 21", chegou.contem("${ESC}]21"))
        assertFalse("DECRQSS", chegou.contem("${ESC}P\$q"))
        assertFalse("OSC 52 de leitura", chegou.contem("52;c;?") || chegou.contem("52;;?"))
    }

    @Test fun oQuePrecisaPassarPassa() {
        val chegou = entrada(Hostil.tudo())
        for (nome in listOf("texto", "da1", "da2", "dsr", "cpr", "cor10", "cor11", "kitty-q", "osc52-escrita", "titulo", "link-https", "final")) {
            assertTrue("Faltou $nome", chegou.contem(Hostil.bytes(nome)))
        }
        // O OSC 52 de **escrita** chega ao termlib, que o entrega ao consentimento.
        assertTrue(chegou.contem("52;c;aGVsbG8="))
    }

    @Test fun cadaAtaqueEConsumidoSozinho() {
        for (nome in listOf("osc52-leitura", "osc52-leitura-st", "relatorio-titulo-21", "relatorio-titulo-20", "osc21", "decrqss", "enq")) {
            assertEquals("O trecho $nome devia sumir inteiro", 0, entrada(Hostil.bytes(nome)).size)
        }
    }

    @Test fun osLinksPerigososNuncaViramIntent() {
        // O OSC 8 chega ao emulador (o texto "clique" aparece); quem decide abrir é `PoliticaDeLinks`, que recusa o esquema.
        assertNull(PoliticaDeLinks.aceitar("javascript:alert(1)"))
        assertNull(PoliticaDeLinks.aceitar("file:///etc/passwd"))
        assertEquals("https://exemplo.org/a", PoliticaDeLinks.aceitar("https://exemplo.org/a"))
    }

    @Test fun respostasPermitidasSaemEAsOutrasNao() {
        // As que o emulador devolve (lista de permitidos).
        for (r in listOf(
            "$ESC[?1;2c", "$ESC[>0;0;0c", "$ESC[0n", "$ESC[12;40R", "$ESC[8;24;80t", "$ESC[4;384;640t",
            "$ESC]10;rgb:ffff/ffff/ffff$BEL", "$ESC]11;rgb:0000/0000/0000$ST", "${ESC}_Gi=50;OK$ST",
        )) assertArrayEquals(r, r.toByteArray(Charsets.ISO_8859_1), saida(r))
        // As que **não** podem sair, mesmo que um emulador as produza.
        for (r in listOf(
            "${ESC}P1\$r0m$ST", // resposta ao DECRQSS
            "$ESC]lbuild-box$ST", // relatório de título
            "${ESC}[21t", // pedido disfarçado de resposta
            "$ESC]52;c;c2VnZWRv$BEL", // conteúdo do clipboard
            "$ESC]52;c;?$BEL",
            "$ESC]21;?$ST",
            "${ESC}_Gi=50;ENOTSUP:" + "x".repeat(400) + ST, // mensagem enorme
            "$ESC]10;rgb:ffff/ffff/ffff;extra$BEL",
        )) assertEquals("Devia ser descartada: ${r.replace(ESC, "ESC")}", 0, saida(r).size)
    }

    @Test fun teclasDoUsuarioSaemIntactas() {
        val teclas = listOf(
            "ls -la\r", "\u0003", "$ESC[A", "${ESC}OA", "${ESC}x", "$ESC[15~", "$ESC[1;5C", "$ESC[Z", "$ESC[200~", "$ESC[201~", ESC, "$ESC[3~",
            "${ESC}[<0;5;3M", "$ESC[I", "${ESC}P", "${ESC}_",
        )
        for (t in teclas) assertArrayEquals(t.replace(ESC, "ESC"), t.toByteArray(Charsets.ISO_8859_1), saida(t))
        assertEquals("ação é UTF-8", "ação", String(FiltroDeRespostas().filtrar("ação".toByteArray(Charsets.UTF_8)), Charsets.UTF_8))
    }

    @Test fun sequenciaTruncadaNaoVazaOResto() {
        assertEquals(0, saida("$ESC]52;c;c2VnZWRv").size)
        assertEquals(0, saida("${ESC}P1\$r0m").size)
        assertEquals(0, saida("$ESC[1;").size)
    }

    @Test fun naoSeJuntaUmaSequenciaProibidaRemovendoOMeio() {
        // `ESC [ 2`, ENQ, `1 t`: um emulador tolerante junta tudo em CSI 21 t. O filtro descarta o pedaço aberto, então nada sobra para juntar.
        val entrada = "${ESC}[2\u00051t".toByteArray(Charsets.ISO_8859_1)
        val saiu = FiltroDaSaidaDoServidor().processar(entrada)
        assertFalse(saiu.contem("${ESC}[21t"))
        assertFalse(saiu.contem("${ESC}[2"))
        // Controle dentro do CSI + o pedido de verdade logo depois.
        val dois = FiltroDaSaidaDoServidor().processar("${ESC}[2\n${ESC}[21t1t".toByteArray(Charsets.ISO_8859_1))
        assertFalse(dois.contem("${ESC}[21t"))
        assertFalse(dois.contem("[21t"))
        // OSC 52 de leitura escondido atrás de um ESC que aborta o prefixo.
        val tres = FiltroDaSaidaDoServidor().processar("${ESC}]5${ESC}[21t2;c;?$BEL".toByteArray(Charsets.ISO_8859_1))
        assertFalse(tres.contem("52;c;?"))
        assertFalse(tres.contem("[21t"))
    }

    @Test fun oTituloVira40CaracteresSemControles() {
        val rotulos = mutableListOf<String>()
        FiltroDaSaidaDoServidor { rotulos += it }.processar(Hostil.bytes("titulo"))
        assertEquals(listOf("build-box: ~/app"), rotulos)
        val longo = mutableListOf<String>()
        FiltroDaSaidaDoServidor { longo += it }.processar("$ESC]2;${"x".repeat(200)}\u0007\u0001".toByteArray())
        assertEquals(40, longo.single().length)
        val controles = mutableListOf<String>()
        FiltroDaSaidaDoServidor { controles += it }.processar("$ESC]0;a\u0001\u0002b‮evil$ST".toByteArray(Charsets.UTF_8))
        assertEquals("abevil", controles.single())
    }
}

private fun ByteArray.contains(b: Int) = any { it.toInt() == b }
