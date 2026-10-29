package com.keyflow.speech

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * FLOC-04/05 e FPRV-02: o módulo de fala local não puxa rede, downloader, OfflineTts nem eSpeak.
 * O processo `:speech` está no manifesto, sem exportar.
 */
class SpeechRulesTest {

    private val main = File("src/main/java")

    private fun kotlinFiles(dir: String): List<File> {
        val root = File(main, dir)
        assertTrue("não achei ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun offenders(files: List<File>, predicate: (String) -> Boolean): List<String> = files.flatMap { file ->
        var inBlock = false
        file.readLines().mapIndexedNotNull { i, line ->
            val code = StringBuilder()
            var j = 0
            while (j < line.length) {
                when {
                    inBlock && line.startsWith("*/", j) -> { inBlock = false; j += 2 }
                    inBlock -> j++
                    line.startsWith("/*", j) -> { inBlock = true; j += 2 }
                    line.startsWith("//", j) -> j = line.length
                    else -> { code.append(line[j]); j++ }
                }
            }
            if (predicate(code.toString())) "${file.name}:$i" else null
        }
    }

    private val localAndFrontend = kotlinFiles("com/keyflow/speech/local") + kotlinFiles("com/keyflow/speech/frontend")

    @Test
    fun falaLocalNaoImportaRedeDownloaderNemOfflineTts() {
        val forbidden = listOf(
            "OfflineTts", "ModelDownloader", "DownloadGateway", "espeak", "eSpeak", "Espeak",
            "java.net.", "okhttp3", "HttpURLConnection", "Socket(", "URL(", "vits-coqui-pt-br",
        )
        val found = offenders(localAndFrontend) { code -> forbidden.any { it in code } }
        assertEquals("fala local sem rede, OfflineTts, downloader ou eSpeak: $found", emptyList<String>(), found)
    }

    @Test
    fun processoSpeechNaoLeConfiguracaoNemCatalogo() {
        val forbidden = listOf(
            "com.keyflow.data.", "androidx.datastore", "SharedPreferences", "ModelStore", "ModelCatalog", "SettingsRepository",
        )
        val service = kotlinFiles("com/keyflow/speech/local").filter { it.name == "SpeechService.kt" || it.name == "SpeechServiceCore.kt" || it.name == "VitsVoice.kt" || it.name == "SupertonicVoice.kt" }
        val found = offenders(service) { code -> forbidden.any { it in code } }
        assertEquals("o :speech recebe caminhos pelo AIDL: $found", emptyList<String>(), found)
    }

    @Test
    fun manifestDeclaraOProcessoSpeechSemExportar() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml")).documentElement
        val services = manifest.getElementsByTagName("service")
        val ns = "http://schemas.android.com/apk/res/android"
        val speech = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == ".speech.local.SpeechService" }
        assertEquals(":speech", speech.getAttributeNS(ns, "process"))
        assertEquals("false", speech.getAttributeNS(ns, "exported"))
    }

    @Test
    fun appCreateSaiDoProcessoSpeechAntesDosCofres() {
        val code = File(main, "com/keyflow/App.kt").readLines()
        val guard = code.first { "SpeechProcess.isCurrent()" in it }
        assertTrue(guard.contains("return"))
        assertTrue(guard.contains("VisionProcess.isCurrent()"))
    }

    @Test
    fun reconheceOProcessoSpeechPeloNome() {
        assertTrue(com.keyflow.speech.local.SpeechProcess.isSpeech("com.arateki.keyflow:speech"))
        for (other in listOf("com.arateki.keyflow", "com.arateki.keyflow:vision", "com.arateki.keyflow:speechx", ":speech", null)) {
            assertTrue("$other não é o :speech", !com.keyflow.speech.local.SpeechProcess.isSpeech(other))
        }
    }

    @Test
    fun urlDoSherpaNaoFoiReaberta() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("sherpa-onnx-") && gradle.contains("sem-tts"))
        assertTrue("OfflineTts" !in gradle)
    }
}
