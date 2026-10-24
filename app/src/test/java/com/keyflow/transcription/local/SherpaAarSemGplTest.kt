package com.keyflow.transcription.local

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nada GPL vai junto com o app (bloqueia a Play Store). O AAR oficial do sherpa-onnx é compilado com
 * `SHERPA_ONNX_ENABLE_TTS=ON`, que embute o eSpeak NG (GPL-3.0) nas `libsherpa-onnx-jni.so` e
 * `libsherpa-onnx-c-api.so`; o KeyFlow usa o AAR recompilado sem TTS (`.github/workflows/sherpa-sem-tts.yml`,
 * publicado como release do repositório e fixado por SHA-256 no `app/build.gradle.kts`).
 *
 * Este teste é a catraca: se alguém voltar ao AAR oficial (ou a um build com TTS), o `testDebugUnitTest` falha
 * com o motivo, em vez de o eSpeak NG ir parar no APK. Procura no AAR, em todo `.so`, os mesmos textos do
 * `strings -a | grep -E` do `scripts/verificar-aar-sherpa.sh`.
 */
class SherpaAarSemGplTest {

    private val padroesDoEspeak = listOf("has not been built into espeak-ng", "Empty _dict file", "espeak_ng_")

    private fun aars(): List<File> {
        val libs = File("libs")
        assertTrue("rode a partir do módulo app/ (não achei ${libs.absolutePath})", libs.isDirectory)
        return libs.listFiles { f -> f.name.startsWith("sherpa-onnx-") && f.extension == "aar" }.orEmpty().sortedBy { it.name }
    }

    @Test
    fun temUmUnicoAarDoSherpaEmLibs() {
        val found = aars()
        assertTrue("app/libs não tem o AAR do sherpa-onnx (o build baixa e confere o SHA-256)", found.isNotEmpty())
        assertEquals(
            "só um AAR do sherpa-onnx em app/libs, o fixado no build (sobrou outro de uma versão antiga?): $found",
            1,
            found.size,
        )
    }

    @Test
    fun aarDoSherpaNaoLevaTextoDoEspeakNg() {
        for (aar in aars()) {
            ZipFile(aar).use { zip ->
                val libs = zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".so") }.toList()
                assertFalse("${aar.name} não tem nenhum .so", libs.isEmpty())
                for (entry in libs) {
                    // Latin-1 mantém um caractere por byte: é o `strings -a` sem o filtro de tamanho.
                    val conteudo = String(zip.getInputStream(entry).use { it.readBytes() }, Charsets.ISO_8859_1)
                    val achados = padroesDoEspeak.filter { conteudo.contains(it) }
                    assertTrue(
                        "${aar.name}!${entry.name} tem código do eSpeak NG (GPL-3.0): $achados. " +
                            "É o AAR oficial do sherpa-onnx, compilado com TTS. Use o AAR sem TTS " +
                            "(.github/workflows/sherpa-sem-tts.yml; URL e SHA-256 em app/build.gradle.kts).",
                        achados.isEmpty(),
                    )
                }
            }
        }
    }
}
