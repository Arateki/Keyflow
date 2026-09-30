package com.keyflow.transcription.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.models.EngineType
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelCatalog
import org.junit.Test
import java.io.File

/** NM-T8: montagem do modelo do engine, ida e volta pelo AIDL, erros e guarda de RAM. */
class EngineModelTest {

    private val dir = File("/data/models/x")

    @Test
    fun `whisper model takes the catalog files and the language hint`() {
        val spec = ModelCatalog.WHISPER_SMALL
        val model = EngineModel.from(spec, dir, "pt")

        assertThat(model.engineType).isEqualTo(EngineType.WHISPER)
        assertThat(model.encoder).isEqualTo(File(dir, spec.file(FileRole.ENCODER)!!.fileName).absolutePath)
        assertThat(model.tokens).isEqualTo(File(dir, spec.file(FileRole.TOKENS)!!.fileName).absolutePath)
        assertThat(model.joiner).isNull()
        assertThat(model.language).isEqualTo("pt")
        assertThat(model.bytes).isEqualTo(spec.totalBytes)
        assertThat(model.files).hasSize(3)
    }

    @Test
    fun `blank language means auto detect`() {
        assertThat(EngineModel.from(ModelCatalog.WHISPER_BASE, dir, " ").language).isNull()
        assertThat(EngineModel.from(ModelCatalog.WHISPER_BASE, dir, null).language).isNull()
    }

    @Test
    fun `parakeet has a joiner and ignores the language hint`() {
        val model = EngineModel.from(ModelCatalog.PARAKEET_V3, dir, "pt")

        assertThat(model.engineType).isEqualTo(EngineType.TRANSDUCER)
        assertThat(model.joiner).isNotNull()
        assertThat(model.language).isNull()
        assertThat(model.files).hasSize(4)
    }

    @Test
    fun `changing only the language is a different model to the engine`() {
        assertThat(EngineModel.from(ModelCatalog.WHISPER_BASE, dir, "pt"))
            .isNotEqualTo(EngineModel.from(ModelCatalog.WHISPER_BASE, dir, "en"))
    }

    @Test
    fun `round trip through the AIDL parcelable`() {
        ModelCatalog.SPEECH.forEach { spec ->
            val model = EngineModel.from(spec, dir, "pt")
            assertThat(EngineModel.fromParcel(model.toParcel())).isEqualTo(model)
        }
    }

    @Test
    fun `error codes are stable and unknown codes read as engine errors`() {
        assertThat(EngineErrorKind.entries.map { it.code }).containsExactly(1, 2, 3, 4).inOrder()
        EngineErrorKind.entries.forEach { assertThat(EngineErrorKind.fromCode(it.code)).isEqualTo(it) }
        assertThat(EngineErrorKind.fromCode(99)).isEqualTo(EngineErrorKind.ENGINE)
    }

    @Test
    fun `memory guard asks for one and a half times the model`() {
        assertThat(MemoryGuard.requiredBytes(1_000)).isEqualTo(1_500)
        assertThat(MemoryGuard.enoughFor(1_000, 1_500)).isTrue()
        assertThat(MemoryGuard.enoughFor(1_000, 1_499)).isFalse()
    }
}
