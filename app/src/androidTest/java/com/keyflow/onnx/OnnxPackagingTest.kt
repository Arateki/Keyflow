package com.keyflow.onnx

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uma só `libonnxruntime.so` por ABI no APK, e é a do AAR do sherpa-onnx (VC-T2b, design §7): a transcrição local é o núcleo do produto
 * e não pode depender de qual `.so` ganha uma disputa de empacotamento. O SHA-256 abaixo é o da `.so` do AAR sem TTS fixado no build
 * (sherpa-onnx 1.13.8, ORT 1.28.2); subir o sherpa muda o hash — e o teste obriga a conferir de novo a ponte JNI (`prepareOrtJava`).
 */
@RunWith(AndroidJUnit4::class)
class OnnxPackagingTest {

    private val pinned = mapOf(
        "arm64-v8a" to "33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1",
        "armeabi-v7a" to "7a8f17a707e52e0ac3a026be319272876d9eae61f4bb19585292772d785f7744",
        "x86_64" to "087e84297cad64acb823c3d901d6e0cc934e91add7cdb20da802cd1c0bea5c3e",
    )

    private fun apks(): List<String> {
        val info = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
        return listOf(info.sourceDir) + info.splitSourceDirs.orEmpty().toList()
    }

    @Test
    fun umaSoLibonnxruntimePorAbiENoMaximoUmaJniPorAbi() {
        val names = apks().flatMap { path -> ZipFile(path).use { zip -> zip.entries().toList().map { it.name } } }
        for (abi in pinned.keys) {
            assertEquals("$abi: libonnxruntime.so", 1, names.count { it == "lib/$abi/libonnxruntime.so" })
            assertEquals("$abi: libonnxruntime4j_jni.so", 1, names.count { it == "lib/$abi/libonnxruntime4j_jni.so" })
        }
        // Nenhuma outra cópia do runtime (ex.: de um AAR que entrou por engano) em lugar nenhum do APK.
        val strays = names.filter { it.endsWith("libonnxruntime.so") && !it.startsWith("lib/") }
        assertTrue("libonnxruntime.so fora de lib/: $strays", strays.isEmpty())
    }

    @Test
    fun aSoEmpacotadaEAByteAByteDaDoSherpa() {
        val abi = Build.SUPPORTED_ABIS.first { it in pinned }
        val sha = apks().firstNotNullOf { path ->
            ZipFile(path).use { zip ->
                zip.getEntry("lib/$abi/libonnxruntime.so")?.let { entry ->
                    MessageDigest.getInstance("SHA-256").digest(zip.getInputStream(entry).use { it.readBytes() })
                        .joinToString("") { "%02x".format(it) }
                }
            }
        }
        assertEquals("libonnxruntime.so de $abi no APK", pinned.getValue(abi), sha)
    }
}
