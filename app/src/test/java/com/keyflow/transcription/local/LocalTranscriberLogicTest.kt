package com.keyflow.transcription.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ErrorKind
import com.keyflow.data.EffectiveConfig
import com.keyflow.data.models.EngineType
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelFile
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ModelStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** NM-T9: o que o cliente local decide sem binder — resolver o modelo e as mensagens de erro. */
class LocalTranscriberLogicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = ModelSpec(
        id = "tiny", displayName = "Tiny", summary = "", languages = "",
        engineType = EngineType.WHISPER, acceptsLanguageHint = true, recommendedRamMb = 1,
        files = listOf(
            ModelFile(FileRole.ENCODER, "enc.onnx", "https://x/enc", "a".repeat(64), 3),
            ModelFile(FileRole.DECODER, "dec.onnx", "https://x/dec", "b".repeat(64), 3),
            ModelFile(FileRole.TOKENS, "tokens.txt", "https://x/tok", "c".repeat(64), 3),
        ),
    )

    private val store by lazy { ModelStore(File(tmp.root, "models")) }
    private val resolver by lazy { LocalModelResolver(store) { if (it == spec.id) spec else null } }

    private fun install() {
        val src = tmp.newFolder("src")
        spec.files.forEach { File(src, it.fileName).writeText("xyz") }
        store.install(spec, src)
    }

    @Test
    fun `an installed model resolves to engine paths with the language`() {
        install()

        val model = resolver.resolve(EffectiveConfig.Local("tiny", "pt"))!!

        assertThat(model.encoder).isEqualTo(File(store.dirFor("tiny"), "enc.onnx").absolutePath)
        assertThat(model.language).isEqualTo("pt")
    }

    @Test
    fun `a model that is not installed or not in the catalog does not resolve`() {
        assertThat(resolver.resolve(EffectiveConfig.Local("tiny", null))).isNull()
        install()
        assertThat(resolver.resolve(EffectiveConfig.Local("gone", null))).isNull()
    }

    @Test
    fun `every engine error has a message for the user`() {
        EngineErrorKind.entries.forEach { kind ->
            assertThat(LocalErrors.message(kind)).isNotEmpty()
        }
        assertThat(LocalErrors.message(EngineErrorKind.OOM)).contains("Memória")
        assertThat(LocalErrors.error("x").kind).isEqualTo(ErrorKind.LOCAL)
        // NAT-03: selecionado mas não baixado é o erro de config de sempre.
        assertThat(LocalErrors.notInstalled.kind).isEqualTo(ErrorKind.CONFIG_MISSING)
    }
}
