package com.keyflow.computador.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapaTecladoFisicoTest {
    private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private val ESC = "\u001b"
    private val ctrl = Modificadores(ctrl = true)
    private val alt = Modificadores(alt = true)

    private fun mapa(t: Tecla, m: Modificadores = Modificadores.Nenhum, app: Boolean = false) = MapaTecladoFisico.bytes(t, m, app)

    @Test fun ctrlLetraVira0x01a0x1a() {
        assertArrayEquals(byteArrayOf(0x03), mapa(Tecla.Caractere('c'), ctrl))
        assertArrayEquals(byteArrayOf(0x01), mapa(Tecla.Caractere('a'), ctrl))
        assertArrayEquals(byteArrayOf(0x1a), mapa(Tecla.Caractere('z'), ctrl))
        assertArrayEquals(byteArrayOf(0x04), mapa(Tecla.Caractere('d'), ctrl))
    }

    @Test fun ctrlAltLetraTemEscNaFrente() {
        assertArrayEquals(byteArrayOf(0x1b, 0x03), mapa(Tecla.Caractere('c'), Modificadores(ctrl = true, alt = true)))
    }

    @Test fun altLetraViraEscLetra() {
        assertArrayEquals(b("${ESC}x"), mapa(Tecla.Caractere('x'), alt))
        assertArrayEquals(b("${ESC}X"), mapa(Tecla.Caractere('x'), Modificadores(alt = true, shift = true)))
    }

    @Test fun letraSemModificadorNaoEMapeada() {
        assertNull(mapa(Tecla.Caractere('a')))
        assertNull(mapa(Tecla.Caractere('a'), Modificadores(shift = true)))
    }

    @Test fun controlesEspeciais() {
        assertArrayEquals(byteArrayOf(0x00), mapa(Tecla.Caractere(' '), ctrl))
        assertArrayEquals(byteArrayOf(0x1b), mapa(Tecla.Caractere('['), ctrl))
        assertArrayEquals(byteArrayOf(0x1c), mapa(Tecla.Caractere('\\'), ctrl))
    }

    @Test fun enterTabEscBackspace() {
        assertArrayEquals(byteArrayOf(0x0d), mapa(Tecla.Enter))
        assertArrayEquals(byteArrayOf(0x09), mapa(Tecla.Tab))
        assertArrayEquals(b("$ESC[Z"), mapa(Tecla.Tab, Modificadores(shift = true)))
        assertArrayEquals(byteArrayOf(0x1b), mapa(Tecla.Esc))
        assertArrayEquals(byteArrayOf(0x7f), mapa(Tecla.Backspace))
        assertArrayEquals(byteArrayOf(0x1b, 0x7f), mapa(Tecla.Backspace, alt))
        assertArrayEquals(b("$ESC[3~"), mapa(Tecla.Delete))
    }

    @Test fun setasNormaisEEmModoDeAplicacao() {
        assertArrayEquals(b("$ESC[A"), mapa(Tecla.Cima))
        assertArrayEquals(b("$ESC[B"), mapa(Tecla.Baixo))
        assertArrayEquals(b("$ESC[C"), mapa(Tecla.Direita))
        assertArrayEquals(b("$ESC[D"), mapa(Tecla.Esquerda))
        assertArrayEquals(b("${ESC}OA"), mapa(Tecla.Cima, app = true))
        assertArrayEquals(b("${ESC}OD"), mapa(Tecla.Esquerda, app = true))
        assertArrayEquals(b("${ESC}OH"), mapa(Tecla.Home, app = true))
        assertArrayEquals(b("$ESC[H"), mapa(Tecla.Home))
        assertArrayEquals(b("$ESC[F"), mapa(Tecla.End))
    }

    @Test fun setaComModificadorUsaFormaDoXterm() {
        assertArrayEquals(b("$ESC[1;5C"), mapa(Tecla.Direita, ctrl))
        assertArrayEquals(b("$ESC[1;3A"), mapa(Tecla.Cima, alt, app = true))
        assertArrayEquals(b("$ESC[1;2B"), mapa(Tecla.Baixo, Modificadores(shift = true)))
        assertArrayEquals(b("$ESC[1;7D"), mapa(Tecla.Esquerda, Modificadores(ctrl = true, alt = true)))
    }

    @Test fun paginasEFuncoes() {
        assertArrayEquals(b("$ESC[5~"), mapa(Tecla.PageUp))
        assertArrayEquals(b("$ESC[6~"), mapa(Tecla.PageDown))
        assertArrayEquals(b("${ESC}OP"), mapa(Tecla.F(1)))
        assertArrayEquals(b("${ESC}OS"), mapa(Tecla.F(4)))
        assertArrayEquals(b("$ESC[15~"), mapa(Tecla.F(5)))
        assertArrayEquals(b("$ESC[17~"), mapa(Tecla.F(6)))
        assertArrayEquals(b("$ESC[21~"), mapa(Tecla.F(10)))
        assertArrayEquals(b("$ESC[23~"), mapa(Tecla.F(11)))
        assertArrayEquals(b("$ESC[24~"), mapa(Tecla.F(12)))
        assertArrayEquals(b("$ESC[15;5~"), mapa(Tecla.F(5), ctrl))
        assertArrayEquals(b("$ESC[1;2P"), mapa(Tecla.F(1), Modificadores(shift = true)))
    }

    @Test fun todasAsTeclasSaemPeloFiltroDeRespostas() {
        // O que o mapa produz nunca é barrado pelo filtro de saída (senão a tecla morreria em silêncio).
        val filtro = FiltroDeRespostas()
        val teclas = listOf(Tecla.Enter, Tecla.Tab, Tecla.Esc, Tecla.Backspace, Tecla.Delete, Tecla.Insert, Tecla.Cima, Tecla.Baixo, Tecla.Esquerda, Tecla.Direita, Tecla.Home, Tecla.End, Tecla.PageUp, Tecla.PageDown) +
            (1..12).map { Tecla.F(it) } + ('a'..'z').map { Tecla.Caractere(it) }
        for (t in teclas) for (m in listOf(Modificadores.Nenhum, ctrl, alt, Modificadores(ctrl = true, alt = true, shift = true))) for (app in listOf(false, true)) {
            val x = mapa(t, m, app) ?: continue
            assertArrayEquals("$t $m $app", x, filtro.filtrar(x))
        }
    }

    @Test fun duplicataDoMesmoEventoEIgnoradaMasTeclasIguaisSeguidasNao() {
        val d = DeduplicadorDeTeclas()
        assertFalse(d.duplicada(0, 29, 1000, 1000, 0))
        assertTrue("mesmo evento por outro caminho", d.duplicada(0, 29, 1000, 1000, 0))
        assertFalse("outra pressão do 'a' (outro downTime)", d.duplicada(0, 29, 1500, 1500, 0))
        assertFalse("tecla segurada repetindo", d.duplicada(0, 29, 1500, 1540, 1))
        assertFalse("soltar é outro evento", d.duplicada(1, 29, 1500, 1600, 0))
        assertTrue(d.duplicada(1, 29, 1500, 1600, 0))
    }

    @Test fun pegajososUmToqueUmaVezDoisTravamTresSoltam() {
        val p = ModificadoresPegajosos()
        assertEquals(Modificadores.Nenhum, p.atuais())
        p.tocarCtrl()
        assertEquals(EstadoPegajoso.UMA_VEZ, p.estadoCtrl())
        assertTrue(p.atuais().ctrl)
        p.consumir()
        assertFalse("uma vez se desfaz", p.atuais().ctrl)
        p.tocarCtrl(); p.tocarCtrl()
        assertEquals(EstadoPegajoso.TRAVADO, p.estadoCtrl())
        p.consumir()
        assertTrue("travado fica", p.atuais().ctrl)
        p.tocarCtrl()
        assertEquals(EstadoPegajoso.DESLIGADO, p.estadoCtrl())
        p.tocarAlt()
        assertEquals(Modificadores(alt = true), p.atuais())
    }

    @Test fun guardaSeguraTextoComQuebraMasNaoOEnterNemOsEscapes() {
        val g = GuardaDeLinhas(janelaMs = 1000)
        fun av(s: String, t: Long = 0) = g.avaliar(s.toByteArray(), t)
        assertTrue(av("\r") === GuardaDeLinhas.Decisao.Enviar)
        assertTrue(av("ls -la") === GuardaDeLinhas.Decisao.Enviar)
        assertTrue(av("olá mundo") === GuardaDeLinhas.Decisao.Enviar)
        assertTrue(av("${ESC}[200~a\nb${ESC}[201~") === GuardaDeLinhas.Decisao.Enviar)
        val segurou = av("olá\nmundo")
        assertTrue(segurou is GuardaDeLinhas.Decisao.Segurar)
        assertEquals("olá\nmundo", (segurou as GuardaDeLinhas.Decisao.Segurar).texto)
        // Colagem já confirmada passa pela janela e volta a segurar depois dela.
        g.liberar(10_000)
        assertTrue(av("a\nb", 10_500) === GuardaDeLinhas.Decisao.Enviar)
        assertTrue(av("a\nb", 11_500) is GuardaDeLinhas.Decisao.Segurar)
    }

    @Test fun modoDeAplicacaoVemDoDeckmDoServidor() {
        val f = FiltroDaSaidaDoServidor()
        assertFalse(f.modoAplicacaoDasSetas)
        f.processar("${ESC}[?1h".toByteArray())
        assertTrue(f.modoAplicacaoDasSetas)
        f.processar("${ESC}[?25l${ESC}[?1;7l".toByteArray())
        assertFalse(f.modoAplicacaoDasSetas)
        f.processar("${ESC}[?1049h".toByteArray())
        assertFalse("1049 não é 1", f.modoAplicacaoDasSetas)
    }
}
