package com.keyflow.onnx

import com.keyflow.onnxbuild.ElfVersionTags
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A ponte JNI do onnxruntime-android roda na `libonnxruntime.so` do sherpa (uma só por ABI, VC-T2b). O linker do Android recusa a
 * `.so` se a etiqueta de versão do símbolo que a JNI exige não for a que a `.so` define — foi o que o emulador mostrou com o
 * `VERS_1.28.0` da JNI contra o `VERS_1.28.2` do sherpa. A tarefa `prepareOrtJava` remenda a etiqueta; estes testes conferem o
 * resultado nos arquivos REAIS (o AAR do sherpa em `libs/` e a saída da tarefa em `build/ortjava/jni`), nas três ABIs.
 * Quem sobe a versão do sherpa ou do onnxruntime-android e deixa as séries divergirem quebra aqui, não no aparelho.
 */
class OnnxRuntimeTagsTest {

    private val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    private fun sherpaAar(): File {
        val found = File("libs").listFiles { f -> f.name.startsWith("sherpa-onnx-") && f.extension == "aar" }.orEmpty()
        assertEquals("um AAR do sherpa em app/libs (o build baixa e confere)", 1, found.size)
        return found.single()
    }

    private fun sherpaRuntime(abi: String): ElfVersionTags =
        ZipFile(sherpaAar()).use { zip ->
            ElfVersionTags.parse(zip.getInputStream(zip.getEntry("jni/$abi/libonnxruntime.so")).use { it.readBytes() })
        }

    private fun patchedJni(abi: String): ElfVersionTags {
        val file = File("build/ortjava/jni/$abi/libonnxruntime4j_jni.so")
        assertTrue("a tarefa prepareOrtJava não gerou ${file.path}", file.isFile)
        return ElfVersionTags.parse(file.readBytes())
    }

    /** Os mesmos SHA-256 do `OnnxPackagingTest` (instrumentado): se o AAR do sherpa mudar, os dois acusam juntos. */
    @Test
    fun aSoDoAarDoSherpaEAFixadaNosTestes() {
        val pinned = mapOf(
            "arm64-v8a" to "33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1",
            "armeabi-v7a" to "7a8f17a707e52e0ac3a026be319272876d9eae61f4bb19585292772d785f7744",
            "x86_64" to "087e84297cad64acb823c3d901d6e0cc934e91add7cdb20da802cd1c0bea5c3e",
        )
        ZipFile(sherpaAar()).use { zip ->
            for ((abi, sha) in pinned) {
                val bytes = zip.getInputStream(zip.getEntry("jni/$abi/libonnxruntime.so")).use { it.readBytes() }
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals("libonnxruntime.so de $abi no AAR do sherpa (subiu a versão? reconfira a ponte JNI)", sha, actual)
            }
        }
    }

    @Test
    fun hashElfBateComODoSherpa() {
        // 41680514 é o vd_hash que o `readelf -V` mostra no `VERS_1.28.2` da libonnxruntime.so do sherpa (medido em 2026-10-27).
        assertEquals(41680514, ElfVersionTags.elfHash("VERS_1.28.2"))
        assertEquals(41680512, ElfVersionTags.elfHash("VERS_1.28.0"))
    }

    @Test
    fun aJniRemendadaExigeExatamenteAVersaoQueOSherpaDefine() {
        for (abi in abis) {
            val defined = sherpaRuntime(abi).definedVersions().filter { it.startsWith("VERS_") }
            assertEquals("$abi: uma versão VERS_* na libonnxruntime.so do sherpa", 1, defined.size)
            val needed = patchedJni(abi).neededVersions().filter { it.file() == "libonnxruntime.so" && it.version().startsWith("VERS_") }
            assertTrue("$abi: a JNI exige versão da libonnxruntime.so", needed.isNotEmpty())
            for (n in needed) {
                assertEquals("$abi: etiqueta exigida pela JNI", defined[0], n.version())
            }
        }
    }

    @Test
    fun oHashDaEtiquetaRemendadaEOMesmoDoVerdefDoSherpa() {
        for (abi in abis) {
            val runtime = sherpaRuntime(abi)
            val defined = runtime.definedVersions().single { it.startsWith("VERS_") }
            // Ida e volta: remendar de novo é idempotente (nada a mudar) e o hash gravado é o do nome.
            val jni = patchedJni(abi)
            assertEquals("$abi: já remendada", 0, jni.retarget("libonnxruntime.so", "VERS_", defined))
            assertEquals(ElfVersionTags.elfHash(defined), readHash(abi))
        }
    }

    /** O hash gravado no Vernaux, lido direto do arquivo (offset que a própria classe informa). */
    private fun readHash(abi: String): Int {
        val bytes = File("build/ortjava/jni/$abi/libonnxruntime4j_jni.so").readBytes()
        val needed = ElfVersionTags.parse(bytes).neededVersions().single { it.file() == "libonnxruntime.so" && it.version().startsWith("VERS_") }
        return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(needed.hashOffset())
    }

    @Test
    fun asJniDoSherpaExigemOQueASoDefine() {
        // Sanidade do próprio sherpa: sua JNI e sua c-api pedem a versão que a sua libonnxruntime.so define.
        for (abi in abis) {
            val defined = sherpaRuntime(abi).definedVersions().single { it.startsWith("VERS_") }
            ZipFile(sherpaAar()).use { zip ->
                for (lib in listOf("libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so")) {
                    val elf = ElfVersionTags.parse(zip.getInputStream(zip.getEntry("jni/$abi/$lib")).use { it.readBytes() })
                    val needed = elf.neededVersions().filter { it.file() == "libonnxruntime.so" }.map { it.version() }.distinct()
                    assertEquals("$abi/$lib", listOf(defined), needed)
                }
            }
        }
    }

    @Test
    fun comprimentoDiferenteNaoCabeNoLugar() {
        val jni = patchedJni("arm64-v8a")
        try {
            jni.retarget("libonnxruntime.so", "VERS_", "VERS_1.28.10")
            fail("era para recusar uma etiqueta de outro comprimento")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("comprimentos diferentes"))
        }
    }

    @Test
    fun arquivoQueNaoEElfEhRecusado() {
        try {
            ElfVersionTags.parse(ByteArray(128))
            fail("não é ELF")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.isNullOrBlank())
        }
    }
}
