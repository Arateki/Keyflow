package com.keyflow.computador.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D17 e CMP-24: regras do terminal que se lê no código e no manifesto (o instrumentado confere a janela de verdade). */
class TerminalActivityRegrasTest {
    private val raizes = listOf(File("src/main/java/com/keyflow/computador/app"), File("src/main/java/com/keyflow/ui/terminal"))
    private fun fontes() = raizes.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test fun nenhumArquivoDoTerminalPedeFlagSecure() {
        val achados = fontes().filter { f -> f.readLines().any { l -> "FLAG_SECURE" in l && !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") && !l.trimStart().startsWith("/*") } }.map { it.name }
        assertEquals("FLAG_SECURE no terminal (D17: a Visão precisa capturar a tela)", emptyList<String>(), achados)
        assertFalse(fontes().any { "setSecure" in it.readText() })
        assertTrue(fontes().isNotEmpty())
    }

    @Test fun aActivityNaoSaiDoApp() {
        val manifesto = File("src/main/AndroidManifest.xml").readText()
        val bloco = manifesto.substringAfter("android:name=\".computador.app.TerminalActivity\"").substringBefore("/>")
        assertTrue(bloco.contains("android:exported=\"false\""))
        assertTrue(bloco.contains("android:windowSoftInputMode=\"adjustResize\""))
        assertTrue(bloco.contains("android:launchMode=\"singleTask\""))
        assertFalse("sem intent-filter", bloco.contains("intent-filter"))
    }

    @Test fun aBancadaDaT1FoiRemovida() {
        assertFalse(File("src/debug/java/com/keyflow/computador/app/TerminalSpikeActivity.kt").exists())
        assertFalse(File("src/debug/AndroidManifest.xml").readText().contains("TerminalSpike"))
    }

    @Test fun aTelaDoTerminalNaoTemAnuncio() {
        val tela = fontes().joinToString("\n") { it.readText() }
        for (proibido in listOf("AdMob", "AdView", "adsController", "Interstitial", "gms.ads")) assertFalse(proibido, tela.contains(proibido))
    }

    @Test fun aFonteEmbutidaTemLicencaOfl() {
        assertTrue(File("src/main/assets/fonts/JetBrainsMonoNerdFontMono-Regular.ttf").length() > 100_000)
        val licenca = File("licenses/OFL_JetBrainsMonoNerdFont.txt").readText()
        assertTrue(licenca.contains("SIL OPEN FONT LICENSE Version 1.1"))
    }
}
