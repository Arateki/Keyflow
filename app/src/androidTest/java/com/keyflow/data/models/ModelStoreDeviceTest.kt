package com.keyflow.data.models

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * DLM-03 no armazenamento interno real do app. Usa uma pasta própria dentro do `filesDir` — nunca a
 * `models/` de verdade, que no aparelho do Yan pode ter modelos baixados.
 */
@RunWith(AndroidJUnit4::class)
class ModelStoreDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "models-test-${System.nanoTime()}")
    private val source = File(context.cacheDir, "model-source-${System.nanoTime()}")

    private val spec = ModelSpec(
        id = "device-fake",
        displayName = "Fake",
        summary = "",
        languages = "",
        engineType = EngineType.TRANSDUCER,
        acceptsLanguageHint = false,
        recommendedRamMb = 1,
        files = listOf(
            ModelFile(FileRole.ENCODER, "encoder.onnx", "https://x/e", "a".repeat(64), BIG),
            ModelFile(FileRole.DECODER, "decoder.onnx", "https://x/d", "b".repeat(64), 16),
            ModelFile(FileRole.JOINER, "joiner.onnx", "https://x/j", "c".repeat(64), 16),
            ModelFile(FileRole.TOKENS, "tokens.txt", "https://x/t", "d".repeat(64), 16),
        ),
    )

    @After
    fun cleanUp() {
        root.deleteRecursively()
        source.deleteRecursively()
    }

    @Test
    fun installThenDeleteFreesTheSpace() {
        source.mkdirs()
        spec.files.forEach { File(source, it.fileName).writeBytes(ByteArray(it.bytes.toInt())) }
        val store = ModelStore(root)

        store.install(spec, source)
        assertEquals(setOf("device-fake"), store.installedIds.value)
        assertTrue(store.dirFor(spec.id).startsWith(context.filesDir))
        val freeWhileInstalled = store.freeBytes()

        store.delete(spec.id)

        assertFalse(store.dirFor(spec.id).exists())
        assertTrue(store.installedIds.value.isEmpty())
        // Folga de metade do arquivo grande: o volume é compartilhado com o resto do aparelho.
        assertTrue(store.freeBytes() - freeWhileInstalled >= BIG / 2)
    }

    private companion object {
        const val BIG = 16L * 1024 * 1024
    }
}
