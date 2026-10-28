package com.keyflow.vision

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regras da Visão e do runtime ONNX viradas teste (design §1, §6; OCR-03; MDL-02), no estilo do `ArchitectureRulesTest`: lê o fonte
 * como texto e roda com o diretório do módulo `app/` como diretório de trabalho.
 */
class VisionRulesTest {

    private val main = File("src/main/java")

    private fun kotlinFiles(dir: String): List<File> {
        val root = File(main, dir)
        assertTrue("não achei ${root.absolutePath} (rode a partir do módulo app/)", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Linhas de código (sem comentários `//` nem blocos `/* */`) de [file] que casam [predicate]. */
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
            if (predicate(code.toString())) "${file.name}:${i + 1}: ${line.trim()}" else null
        }
    }

    private val networkTokens = listOf(
        "java.net.", "okhttp3", "android.net.", "HttpURLConnection", "Socket(", "URL(", "InetAddress", "javax.net", "android.webkit",
    )

    /** OCR-03: nada que fale rede no OCR, no runtime ONNX nem no serviço do `:vision`. */
    @Test
    fun ocrRuntimeEServicoNaoImportamRede() {
        val files = kotlinFiles("com/keyflow/vision/ocr") + kotlinFiles("com/keyflow/vision/service") + kotlinFiles("com/keyflow/onnx")
        val found = offenders(files) { code -> networkTokens.any { it in code } }
        assertEquals("o :vision e o runtime ONNX não fazem rede (OCR-03): $found", emptyList<String>(), found)
    }

    /** As etapas do OCR são Kotlin puro: nada de `android.` (é o que as deixa testáveis na JVM). */
    @Test
    fun etapasDoOcrNaoUsamAndroid() {
        val found = offenders(kotlinFiles("com/keyflow/vision/ocr")) { "android." in it }
        assertEquals("vision/ocr roda na JVM nos testes: sem android.* ($found)", emptyList<String>(), found)
    }

    /** O `:vision` recebe tudo pelo AIDL: não lê Settings, DataStore, catálogo nem o armazenamento de modelos. */
    @Test
    fun processoVisionNaoLeConfiguracaoNemCatalogo() {
        val forbidden = listOf(
            "com.keyflow.data.", "androidx.datastore", "SharedPreferences", "ModelStore", "ModelCatalog", "SettingsRepository",
        )
        val found = offenders(kotlinFiles("com/keyflow/vision/service") + kotlinFiles("com/keyflow/vision/ocr")) { code ->
            forbidden.any { it in code }
        }
        assertEquals("o :vision não lê Settings/DataStore/catálogo (caminhos chegam pelo AIDL): $found", emptyList<String>(), found)
    }

    /** MDL-02: a Visão só CONSULTA se o modelo está instalado; baixar é da aba Visão. */
    @Test
    fun visaoNaoImportaODownloader() {
        val forbidden = listOf("ModelDownloader", "DownloadGateway", "ModelDownloadService")
        val found = offenders(kotlinFiles("com/keyflow/vision")) { code -> forbidden.any { it in code } }
        assertEquals("a Visão não baixa modelo (MDL-02): $found", emptyList<String>(), found)
    }

    @Test
    fun manifestDeclaraOServicoNoProcessoVisionSemExportar() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml")).documentElement
        val services = manifest.getElementsByTagName("service")
        val ns = "http://schemas.android.com/apk/res/android"
        val vision = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == ".vision.service.VisionService" }
        assertEquals(":vision", vision.getAttributeNS(ns, "process"))
        assertEquals("false", vision.getAttributeNS(ns, "exported"))
    }

    /**
     * O `Application.onCreate()` roda em todo processo, `:vision` inclusive. Ele abre cofres e coleta Settings/DataStore; no `:vision` (que
     * recebe tudo pelo AIDL, design §6) nada disso pode rodar. A guarda por nome de processo tem de vir ANTES de qualquer acesso.
     */
    @Test
    fun appCreateSaiAntesDeAbrirCofresEConfiguracaoNoProcessoVision() {
        val code = File(main, "com/keyflow/App.kt").readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") || it.trim().startsWith("import ") }
        val guard = code.indexOfFirst { "VisionProcess.isCurrent()" in it }
        assertTrue("App.onCreate precisa da guarda VisionProcess.isCurrent()", guard >= 0)
        assertTrue("a guarda sai do onCreate (return)", "return" in code[guard])
        val accesses = listOf("SettingsRepository(", "secretVault", "authServices.store", "cleanupStaleRecordings", "StrictModeGuard.install")
        val early = code.withIndex().filter { (i, line) -> i < guard && accesses.any { it in line } }.map { it.value.trim() }
        assertEquals("nada de cofre/Settings antes da guarda do :vision: $early", emptyList<String>(), early)
        // E o inverso: a guarda existe porque o manifest declara serviço no :vision (se o processo sumir, a regra pode sair junto).
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(":vision" in manifest)
    }

    @Test
    fun reconheceOProcessoVisionPeloNome() {
        assertTrue(com.keyflow.vision.service.VisionProcess.isVision("com.arateki.keyflow:vision"))
        for (other in listOf("com.arateki.keyflow", "com.arateki.keyflow:inference", "com.arateki.keyflow:reasoning", "com.arateki.keyflow:visionx", ":vision", "", null)) {
            assertTrue("$other não é o :vision", !com.keyflow.vision.service.VisionProcess.isVision(other))
        }
    }
}
