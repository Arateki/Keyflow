package com.keyflow.vision

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/** Portão da VC-T15: o cliente local e o processo de inferência não ganham transporte HTTP ou download. */
class VisionPrivacyTest {
    @Test fun caminhoLocalNaoImportaRedeNemDownloader() {
        val roots = listOf(File("src/main/java/com/keyflow/reasoning/local"), File("src/main/java/com/keyflow/vision"))
        val files = roots.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        for (file in files.filter { it.name != "CustomVisionTransport.kt" }) {
            val imports = file.readLines().filter { it.trimStart().startsWith("import ") }.joinToString("\n")
            for (forbidden in listOf("okhttp3", "java.net", "ModelDownloader", "Retrofit")) {
                assertFalse("${file.name} importou $forbidden no caminho local", forbidden in imports)
            }
        }
    }
    @Test fun falhasDoMotorNaoGravamMensagensBrutasEmLog() {
        val logs = File("src/main/java/com/keyflow/reasoning/local/ReasoningService.kt").readLines().filter { "Log." in it }
        assertFalse("a falha pode incluir o conteúdo da imagem", logs.any { "\$message" in it || "\$reason" in it })
    }

}
