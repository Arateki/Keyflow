package com.keyflow.onnx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A ONNX Runtime do app no aparelho (VC-T2b): a ponte JNI do `onnxruntime-android`, remendada no build, rodando na
 * `libonnxruntime.so` do sherpa. Abre um `.onnx` mínimo (dois `Add`, gerado por `scripts/gerar-onnx-minimo.py`) e confere as contas.
 * Roda na ABI do aparelho: x86_64 no emulador, arm64-v8a no A32. Se a etiqueta de versão do símbolo não bater, o `System.loadLibrary`
 * falha aqui com "cannot locate symbol OrtGetApiBase".
 */
@RunWith(AndroidJUnit4::class)
class OnnxSessionTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val model = File(instrumentation.targetContext.cacheDir, "onnx-test/add.onnx")

    @Before
    fun copyModel() {
        model.parentFile!!.mkdirs()
        instrumentation.context.assets.open("onnx/add.onnx").use { input -> model.outputStream().use { input.copyTo(it) } }
    }

    @After
    fun cleanUp() {
        model.parentFile?.deleteRecursively()
    }

    @Test
    fun modeloDeTesteEOVersionado() {
        assertEquals(ADD_ONNX_SHA256, sha256(model))
    }

    @Test
    fun runtimeCarregaERespondeComAVersaoDoSherpa() {
        assertTrue("a ORT não carregou: ${OnnxRuntime.unavailableReason}", OnnxRuntime.available)
        assertTrue("versão ${OnnxRuntime.version}", OnnxRuntime.version.orEmpty().startsWith("1.28."))
        assertEquals(null, OnnxRuntime.unavailableReason)
    }

    @Test
    fun somaFloatELongComoOEsperado() {
        OnnxSession.open(model.path).use { session ->
            assertEquals(listOf("x", "y", "a", "b"), session.inputNames)
            assertEquals(listOf("z", "w"), session.outputNames)
            val out = session.run(
                listOf(
                    FloatTensor(longArrayOf(3), floatArrayOf(1f, 2f, 3f)),
                    FloatTensor(longArrayOf(3), floatArrayOf(10f, 20f, 30f)),
                    LongTensor(longArrayOf(3), longArrayOf(1, 2, 3)),
                    LongTensor(longArrayOf(3), longArrayOf(4, 5, 6)),
                ),
            )
            val z = out[0] as FloatTensor
            val w = out[1] as LongTensor
            assertArrayEquals(longArrayOf(3), z.shape)
            assertArrayEquals(floatArrayOf(11f, 22f, 33f), z.data, 0f)
            assertArrayEquals(longArrayOf(5, 7, 9), w.data)
        }
    }

    @Test
    fun formaDinamicaEVariasChamadasNaMesmaSessao() {
        OnnxSession.open(model.path, threads = 1).use { session ->
            for (n in listOf(1, 5, 4096)) {
                val x = FloatArray(n) { it.toFloat() }
                val out = session.run(
                    listOf(
                        FloatTensor(longArrayOf(n.toLong()), x),
                        FloatTensor(longArrayOf(n.toLong()), FloatArray(n) { 0.5f }),
                        LongTensor(longArrayOf(n.toLong()), LongArray(n)),
                        LongTensor(longArrayOf(n.toLong()), LongArray(n) { 2L }),
                    ),
                )
                val z = out[0] as FloatTensor
                assertEquals(n, z.data.size)
                assertEquals(n - 1 + 0.5f, z.data[n - 1], 0f)
                assertEquals(2L, (out[1] as LongTensor).data[0])
            }
        }
    }

    @Test
    fun tensoresDeMenosOuDeMaisSaoRecusadosComOnnxException() {
        OnnxSession.open(model.path).use { session ->
            try {
                session.run(listOf(FloatTensor(longArrayOf(1), floatArrayOf(1f))))
                fail("faltam entradas")
            } catch (e: OnnxException) {
                assertTrue(e.message.orEmpty().contains("4 entrada"))
            }
        }
    }

    @Test
    fun tipoDeTensorQueOModeloNaoAceitaViraOnnxExceptionENaoCrash() {
        OnnxSession.open(model.path).use { session ->
            try {
                // `x` e `y` são float; mandar long onde o modelo pede float.
                session.run(
                    listOf(
                        LongTensor(longArrayOf(1), longArrayOf(1)), LongTensor(longArrayOf(1), longArrayOf(1)),
                        LongTensor(longArrayOf(1), longArrayOf(1)), LongTensor(longArrayOf(1), longArrayOf(1)),
                    ),
                )
                fail("tipo errado")
            } catch (e: OnnxException) {
                assertTrue(e.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun arquivoInexistenteOuCorrompidoViraOnnxException() {
        for (bad in listOf(File(model.parentFile, "nao-existe.onnx"), File(model.parentFile, "lixo.onnx").also { it.writeBytes(ByteArray(64) { b -> b.toByte() }) })) {
            try {
                OnnxSession.open(bad.path).close()
                fail("devia recusar ${bad.name}")
            } catch (e: OnnxException) {
                assertTrue(e.message.orEmpty().contains(bad.path))
            }
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Saída do `scripts/gerar-onnx-minimo.py` (determinística). */
        const val ADD_ONNX_SHA256 = "66ef9c401e44d8d1c61131c9443312e92edaf8ffa1527923945f77a264523d1c"
    }
}
