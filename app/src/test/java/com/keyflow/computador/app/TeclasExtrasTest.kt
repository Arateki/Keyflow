package com.keyflow.computador.app

import com.keyflow.ui.terminal.teclasDeFuncao
import com.keyflow.ui.terminal.teclasExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CMP-26 e CMP-31: as teclas extras que o spec lista, todas com descrição para o leitor de tela. */
class TeclasExtrasTest {
    @Test fun primeiraLinhaTemAsTeclasDoSpec() {
        assertEquals(listOf("esc", "tab", "ctrl", "alt", "esq", "baixo", "cima", "dir", "pipe", "til", "barra", "hifen"), teclasExtras().map { it.id })
        assertEquals(listOf("Esc", "Tab", "Ctrl", "Alt", "←", "↓", "↑", "→", "|", "~", "/", "-"), teclasExtras().map { it.rotulo })
    }

    @Test fun segundaLinhaTemF1AteF12() {
        assertEquals((1..12).map { "F$it" }, teclasDeFuncao().map { it.rotulo })
    }

    @Test fun todaTeclaTemDescricaoUnica() {
        val todas = teclasExtras() + teclasDeFuncao()
        assertTrue(todas.all { it.descricao.isNotBlank() })
        assertEquals(todas.size, todas.map { it.descricao }.toSet().size)
    }

    @Test fun apenasCtrlEAltSaoPegajosas() {
        assertEquals(listOf("ctrl", "alt"), (teclasExtras() + teclasDeFuncao()).filter { it.pegajosa }.map { it.id })
    }
}
