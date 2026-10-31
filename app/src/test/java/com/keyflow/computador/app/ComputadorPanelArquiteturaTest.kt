package com.keyflow.computador.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/** CMP-08: sem teste simulado. O painel não importa `delay` e a frase fixa antiga sumiu do código. */
class ComputadorPanelArquiteturaTest {
    private val arquivos = listOf("ComputadorPanel.kt", "ComputadorHosts.kt", "ComputadorHostModal.kt").map { File("src/main/java/com/keyflow/ui/config/$it") }

    @Test fun painelNaoImportaDelay() {
        for (f in arquivos) assertFalse("${f.name} importa delay", f.readLines().any { it.trim() == "import kotlinx.coroutines.delay" })
    }

    @Test fun fraseFixaESemSimulacaoSairamDoCodigo() {
        val todo = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(todo.contains("Configuração SSH válida"))
        assertFalse(arquivos.any { Regex("""\bdelay\(""").containsMatchIn(it.readText()) })
    }
}
