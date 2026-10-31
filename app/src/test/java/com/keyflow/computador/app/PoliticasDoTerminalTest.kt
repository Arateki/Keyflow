package com.keyflow.computador.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliticasDoTerminalTest {
    private val ESC = "\u001b"

    @Test fun colarUmaLinhaNaoPedeConfirmacao() {
        val p = PoliticaDeColar.preparar("ls -la")
        assertFalse(p.precisaConfirmar)
        assertEquals(1, p.linhas)
        assertEquals("ls -la", p.texto)
    }

    @Test fun colarVariasLinhasPedeConfirmacaoEContaAsLinhas() {
        val p = PoliticaDeColar.preparar("a\nb")
        assertTrue(p.precisaConfirmar)
        assertEquals(2, p.linhas)
        assertEquals(3, PoliticaDeColar.preparar("a\r\nb\rc").linhas)
        // Uma quebra no fim executa o comando: também confirma.
        assertTrue(PoliticaDeColar.preparar("rm -rf x\n").precisaConfirmar)
        assertTrue(PoliticaDeColar.preparar("a\rb").precisaConfirmar)
    }

    @Test fun colarRemoveOFimDoBracketedPaste() {
        assertEquals("ab", PoliticaDeColar.preparar("a$ESC[201~b").texto)
        assertEquals("ab", PoliticaDeColar.preparar("a$ESC[200~b").texto)
    }

    @Test fun colarComMarcaAninhadaNaoReconstroiOFim() {
        // Remover uma vez só deixaria `ESC [ 2 0 1 ~` de pé: o texto de dentro forma a marca de novo.
        val p = PoliticaDeColar.preparar("a$ESC$ESC[201~[201~b; rm -rf ~")
        assertFalse(p.texto.contains("[201~"))
        assertFalse(p.texto.contains(ESC))
    }

    @Test fun colarRemoveControlesMasMantemTabENovaLinha() {
        val p = PoliticaDeColar.preparar("a\tb\u0001\u0007\u007f\u009bc\nd")
        assertEquals("a\tbc\nd", p.texto)
    }

    @Test fun colarVazioNaoTemLinhas() {
        assertEquals(0, PoliticaDeColar.preparar("").linhas)
        assertEquals("", PoliticaDeColar.preparar("$ESC[201~").texto)
    }

    @Test fun linksSoHttpHttpsEMailto() {
        assertEquals("https://exemplo.org/a?b=c#d", PoliticaDeLinks.aceitar(" https://exemplo.org/a?b=c#d "))
        assertEquals("HTTP://exemplo.org", PoliticaDeLinks.aceitar("HTTP://exemplo.org"))
        assertEquals("mailto:yan@exemplo.org", PoliticaDeLinks.aceitar("mailto:yan@exemplo.org"))
        for (ruim in listOf(
            "javascript:alert(1)", "JaVaScRiPt:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "content://x/y", "data:text/html,x", "tel:123", "ssh://h",
            "https:exemplo.org", "https://", "https://user@exemplo.org", "https://exemplo.org/a b", "https://a.b/\u0000", "https://a.b/‮", "/relativo", "", "sem-esquema",
            "https://" + "a".repeat(3000), "ht tp://a.b", "java\nscript:x",
        )) assertNull("devia recusar: $ruim", PoliticaDeLinks.aceitar(ruim))
    }

    @Test fun rotuloSemControlesEAte40() {
        assertEquals("build-box", RotuloDeAba.sanitizar("  build-box \u0001"))
        assertEquals("a b", RotuloDeAba.sanitizar("a\n\n  b"))
        assertNull(RotuloDeAba.sanitizar("\u0001\u0002 ‮"))
        assertEquals(40, RotuloDeAba.sanitizar("x".repeat(100))!!.length)
        assertEquals("日本語ação", RotuloDeAba.sanitizar("日本語ação"))
        // Par substituto (emoji) nunca é cortado ao meio.
        val emoji = RotuloDeAba.sanitizar("😀".repeat(60))!!
        assertEquals(40, emoji.codePointCount(0, emoji.length))
        assertFalse(emoji.last().isHighSurrogate())
    }
}
