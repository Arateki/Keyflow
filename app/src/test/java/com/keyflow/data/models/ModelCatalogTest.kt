package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** CAT-01/02: integridade do catálogo embutido — o que o downloader e o engine assumem dele. */
class ModelCatalogTest {

    // Estas checagens são do catálogo de FALA (papéis do sherpa-onnx, repositórios csukuangfj); o de texto tem as suas.
    private val models = ModelCatalog.SPEECH

    @Test
    fun `catalog has the three curated models with unique ids`() {
        assertThat(models.map { it.id })
            .containsExactly("whisper-base-int8", "whisper-small-int8", "parakeet-tdt-0.6b-v3-int8")
            .inOrder()
    }

    @Test
    fun `every file has a sha256 of 64 lowercase hex and a positive size`() {
        models.flatMap { it.files }.forEach { file ->
            assertThat(file.sha256).matches("[0-9a-f]{64}")
            assertThat(file.bytes).isGreaterThan(0L)
        }
    }

    @Test
    fun `each model has exactly one tokens file and one file per role`() {
        models.forEach { model ->
            val roles = model.files.map { it.role }
            assertThat(roles.count { it == FileRole.TOKENS }).isEqualTo(1)
            assertThat(roles).containsNoDuplicates()
        }
    }

    /** O engine monta a config por papel: Whisper = encoder+decoder, transducer = + joiner. */
    @Test
    fun `roles match what each engine type needs`() {
        models.forEach { model ->
            val roles = model.files.map { it.role }.toSet()
            val expected = when (model.engineType) {
                EngineType.WHISPER -> setOf(FileRole.ENCODER, FileRole.DECODER, FileRole.TOKENS)
                EngineType.TRANSDUCER -> setOf(FileRole.ENCODER, FileRole.DECODER, FileRole.JOINER, FileRole.TOKENS)
                EngineType.LITERT_LM -> error("${model.id}: motor de texto no catálogo de fala")
            }
            assertThat(roles).isEqualTo(expected)
        }
    }

    /** G5: fixar o commit é o que garante que o SHA do catálogo continue batendo com o que se baixa. */
    @Test
    fun `urls point to a pinned huggingface commit and end with the file name`() {
        models.flatMap { it.files }.forEach { file ->
            assertThat(file.url).matches("https://huggingface\\.co/csukuangfj/[^/]+/resolve/[0-9a-f]{40}/.+")
            assertThat(file.url).endsWith("/${file.fileName}")
        }
    }

    @Test
    fun `only whisper takes a language hint`() {
        models.forEach { model ->
            assertThat(model.acceptsLanguageHint).isEqualTo(model.engineType == EngineType.WHISPER)
        }
    }

    @Test
    fun `each model names the upstream model it is, for the card subtitle`() {
        assertThat(models.map { it.modelName })
            .containsExactly("Whisper base", "Whisper small", "Parakeet TDT 0.6B v3").inOrder()
    }

    @Test
    fun `models are listed from lightest to heaviest`() {
        assertThat(models.map { it.totalBytes }).isInOrder()
        assertThat(models.map { it.recommendedRamMb }).isInOrder()
    }

    @Test
    fun `total sizes match the upstream files`() {
        assertThat(ModelCatalog.WHISPER_BASE.totalBytes).isEqualTo(160_609_290L)
        assertThat(ModelCatalog.WHISPER_SMALL.totalBytes).isEqualTo(375_485_327L)
        assertThat(ModelCatalog.PARAKEET_V3.totalBytes).isEqualTo(670_478_772L)
    }

    @Test
    fun `lookup by id`() {
        assertThat(ModelCatalog.byId("whisper-small-int8")).isSameInstanceAs(ModelCatalog.WHISPER_SMALL)
        assertThat(ModelCatalog.byId("inexistente")).isNull()
        assertThat(ModelCatalog.byId(null)).isNull()
        assertThat(ModelCatalog.PARAKEET_V3.file(FileRole.JOINER)?.fileName).isEqualTo("joiner.int8.onnx")
        assertThat(ModelCatalog.WHISPER_BASE.file(FileRole.JOINER)).isNull()
    }

    // ── RCAT-01/05: o catálogo separa fala e texto ─────────────────────────────────────────────

    @Test
    fun `speech catalog is exactly the three existing models`() {
        assertThat(ModelCatalog.SPEECH)
            .containsExactly(ModelCatalog.WHISPER_BASE, ModelCatalog.WHISPER_SMALL, ModelCatalog.PARAKEET_V3)
            .inOrder()
    }

    @Test
    fun `every existing spec defaults to speech`() {
        assertThat(ModelCatalog.SPEECH.map { it.kind }.toSet()).containsExactly(ModelKind.SPEECH)
    }

    @Test
    fun `models is speech plus text`() {
        assertThat(ModelCatalog.MODELS).isEqualTo(ModelCatalog.SPEECH + ModelCatalog.TEXT)
    }

    @Test
    fun `byId resolves any kind`() {
        assertThat(ModelCatalog.byId(ModelCatalog.SPEECH.first().id)).isNotNull()
        ModelCatalog.TEXT.forEach { assertThat(ModelCatalog.byId(it.id)).isSameInstanceAs(it) }
    }

    @Test
    fun `text specs never leak into the speech list and vice versa`() {
        assertThat(ModelCatalog.SPEECH.map { it.kind }).doesNotContain(ModelKind.TEXT)
        assertThat(ModelCatalog.TEXT.map { it.kind }).doesNotContain(ModelKind.SPEECH)
    }
}
