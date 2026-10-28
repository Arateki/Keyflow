package com.keyflow.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxRuntimeTest {

    @Test
    fun versaoPresenteViraDisponivel() {
        val probe = OnnxRuntime.probeWith { " 1.28.2 " }
        assertEquals("1.28.2", probe.version)
        assertNull(probe.failure)
    }

    @Test
    fun versaoVaziaOuNulaEIndisponivelSemExcecao() {
        for (answer in listOf("", "   ", null)) {
            val probe = OnnxRuntime.probeWith { answer }
            assertNull(probe.version)
            assertNotNull(probe.failure)
        }
    }

    @Test
    fun bibliotecaNativaQueNaoCarregaViraIndisponivelSemCrash() {
        val link = OnnxRuntime.probeWith { throw UnsatisfiedLinkError("dlopen failed: cannot locate symbol \"OrtGetApiBase\"") }
        assertNull(link.version)
        assertTrue(link.failure.orEmpty().contains("UnsatisfiedLinkError"))
        assertTrue(link.failure.orEmpty().contains("OrtGetApiBase"))
        // NoClassDefFoundError (a classe nativa já falhou uma vez) é Error, não Exception: também não escapa.
        assertNull(OnnxRuntime.probeWith { throw NoClassDefFoundError("ai/onnxruntime/OnnxRuntime") }.version)
        assertNull(OnnxRuntime.probeWith { throw IllegalStateException("x") }.version)
    }

    @Test
    fun motivoLongoEhCortado() {
        val probe = OnnxRuntime.probeWith { throw RuntimeException("x".repeat(5_000)) }
        assertTrue(probe.failure.orEmpty().length <= 300)
    }

    @Test
    fun naJvmSemANativaOmoduloNaoQuebra() {
        OnnxRuntime.resetForTest()
        // No host não há libonnxruntime para Android: a sondagem real tem de responder, não lançar.
        val available = OnnxRuntime.available
        if (available) assertNotNull(OnnxRuntime.version) else assertNotNull(OnnxRuntime.unavailableReason)
        if (!available) {
            try {
                OnnxSession.open("/nao/existe.onnx")
            } catch (e: OnnxException) {
                assertTrue(e.message.orEmpty().contains("indisponível"))
                return
            }
            throw AssertionError("abrir sessão sem runtime devia falhar com OnnxException")
        }
    }

    @Test
    fun tensorConfereAFormaComOsDados() {
        assertEquals(6L, FloatTensor(longArrayOf(2, 3), FloatArray(6)).elementCount)
        assertEquals(1L, FloatTensor(longArrayOf(), FloatArray(1)).elementCount)
        for (bad in listOf<() -> Tensor>(
            { FloatTensor(longArrayOf(2, 3), FloatArray(5)) },
            { LongTensor(longArrayOf(-1, 3), LongArray(3)) },
        )) {
            try {
                bad()
                throw AssertionError("forma incompatível devia ser recusada")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.isNullOrBlank())
            }
        }
    }
}
