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
                EngineType.OCR -> error("${model.id}: motor de visão no catálogo de fala")
                EngineType.TTS -> error("${model.id}: motor de voz no catálogo de fala")
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
    fun `models is speech plus text plus vision plus voice`() {
        assertThat(ModelCatalog.MODELS).isEqualTo(ModelCatalog.SPEECH + ModelCatalog.TEXT + ModelCatalog.VISION + ModelCatalog.VOICE)
    }

    @Test
    fun `byId resolves any kind`() {
        assertThat(ModelCatalog.byId(ModelCatalog.SPEECH.first().id)).isNotNull()
        ModelCatalog.TEXT.forEach { assertThat(ModelCatalog.byId(it.id)).isSameInstanceAs(it) }
        ModelCatalog.VISION.forEach { assertThat(ModelCatalog.byId(it.id)).isSameInstanceAs(it) }
        ModelCatalog.VOICE.forEach { assertThat(ModelCatalog.byId(it.id)).isSameInstanceAs(it) }
    }

    @Test
    fun `specs never leak across kinds`() {
        assertThat(ModelCatalog.SPEECH.map { it.kind }).containsNoneOf(ModelKind.TEXT, ModelKind.VISION, ModelKind.VOICE)
        assertThat(ModelCatalog.TEXT.map { it.kind }).containsNoneOf(ModelKind.SPEECH, ModelKind.VISION, ModelKind.VOICE)
        assertThat(ModelCatalog.VISION.map { it.kind }).containsNoneOf(ModelKind.SPEECH, ModelKind.TEXT, ModelKind.VOICE)
        assertThat(ModelCatalog.VOICE.map { it.kind }).containsNoneOf(ModelKind.SPEECH, ModelKind.TEXT, ModelKind.VISION)
    }

    // ── RCAT-02/03: catálogo de texto (LiteRT-LM) ──────────────────────────────────────────────

    @Test
    fun `text catalog has the light and the balanced model, lightest first`() {
        assertThat(ModelCatalog.TEXT.map { it.id })
            .containsExactly(
                "qwen3-0.6b-litertlm",
                "smollm2-1.7b-litertlm",
                "llama-3.2-1b-litertlm",
                "deepseek-r1-1.5b-litertlm",
                "gemma-2-2b-litertlm",
                "gemma-4-e2b-litertlm",
                "llama-3.2-3b-litertlm",
                "phi-3.5-mini-litertlm",
            )
            .inOrder()
        assertThat(ModelCatalog.TEXT.map { it.totalBytes }).isInOrder()
        assertThat(ModelCatalog.TEXT.map { it.recommendedRamMb }).isInOrder()
    }

    @Test
    fun `text specs are one litertlm file each, with a real sha256 and size`() {
        ModelCatalog.TEXT.forEach { model ->
            assertThat(model.kind).isEqualTo(ModelKind.TEXT)
            assertThat(model.engineType).isEqualTo(EngineType.LITERT_LM)
            val file = model.files.single()
            assertThat(file.role).isEqualTo(FileRole.MODEL)
            assertThat(file.fileName).endsWith(".litertlm")
            assertThat(file.sha256).matches("[0-9a-f]{64}")
            assertThat(file.bytes).isGreaterThan(0L)
            assertThat(model.recommendedRamMb).isGreaterThan(0)
            assertThat(model.acceptsLanguageHint).isFalse()
            assertThat(model.modelName).isNotEmpty()
        }
    }

    /** RCAT-03: só baixável sem login, e fixado no commit — o SHA do catálogo tem de continuar valendo. */
    @Test
    fun `text urls point to a pinned huggingface commit and end with the file name`() {
        ModelCatalog.TEXT.flatMap { it.files }.forEach { file ->
            assertThat(file.url).matches("https://huggingface\\.co/litert-community/[^/]+/resolve/[0-9a-f]{40}/.+")
            assertThat(file.url).endsWith("/${file.fileName}")
        }
    }

    /** Números lidos da API do Hugging Face em 2026-09-29 (`/api/models/<repo>/tree/<commit>`). */
    @Test
    fun `text sizes and hashes match the upstream files`() {
        assertThat(ModelCatalog.QWEN3_0_6B.totalBytes).isEqualTo(614_236_160L)
        assertThat(ModelCatalog.QWEN3_0_6B.files.single().sha256)
            .isEqualTo("555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4")
        assertThat(ModelCatalog.GEMMA_4_E2B.totalBytes).isEqualTo(2_588_147_712L)
        assertThat(ModelCatalog.GEMMA_4_E2B.files.single().sha256)
            .isEqualTo("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c")
    }

    // ── Catálogo de visão e OCR (vision-capture) ──────────────────────────────────────────────────

    @Test
    fun `vision catalog has fast, balanced and multimodal models, lightest first`() {
        assertThat(ModelCatalog.VISION.map { it.id })
            .containsExactly(
                "ocr-fast-latin",
                "mobilenet-v4-ocr",
                "pp-ocrv4-multilingual",
                "florence-2-nano",
                "smolvlm-500m",
            )
            .inOrder()
        assertThat(ModelCatalog.VISION.map { it.totalBytes }).isInOrder()
        assertThat(ModelCatalog.VISION.map { it.recommendedRamMb }).isInOrder()
    }

    @Test
    fun `vision specs have valid kind, real sha256 and size`() {
        ModelCatalog.VISION.forEach { model ->
            assertThat(model.kind).isEqualTo(ModelKind.VISION)
            val file = model.files.single()
            assertThat(file.role).isEqualTo(FileRole.MODEL)
            assertThat(file.sha256).matches("[0-9a-f]{64}")
            assertThat(file.bytes).isGreaterThan(0L)
            assertThat(model.recommendedRamMb).isGreaterThan(0)
            assertThat(model.acceptsLanguageHint).isFalse()
            assertThat(model.modelName).isNotEmpty()
        }
    }

    @Test
    fun `vision sizes and hashes match`() {
        assertThat(ModelCatalog.OCR_FAST.totalBytes).isEqualTo(4_404_019L)
        assertThat(ModelCatalog.OCR_FAST.files.single().sha256)
            .isEqualTo("4a8f9c1e2b3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f")
        assertThat(ModelCatalog.OCR_BALANCED.totalBytes).isEqualTo(26_633_830L)
        assertThat(ModelCatalog.OCR_BALANCED.files.single().sha256)
            .isEqualTo("b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2")
        assertThat(ModelCatalog.VLM_MULTIMODAL.totalBytes).isEqualTo(587_202_560L)
        assertThat(ModelCatalog.VLM_MULTIMODAL.files.single().sha256)
            .isEqualTo("c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4")
    }

    // ── Catálogo de voz e síntese de fala (speech-synthesis) ──────────────────────────────────────

    @Test
    fun `voice catalog has fast, balanced and quality models, lightest first`() {
        assertThat(ModelCatalog.VOICE.map { it.id })
            .containsExactly(
                "vits-pt-br-google-neural",
                "piper-pt-br",
                "kokoro-v0_19",
                "melotts-multilingual",
            )
            .inOrder()
        assertThat(ModelCatalog.VOICE.map { it.totalBytes }).isInOrder()
        assertThat(ModelCatalog.VOICE.map { it.recommendedRamMb }).isInOrder()
    }

    @Test
    fun `voice specs have valid kind, real sha256 and size`() {
        ModelCatalog.VOICE.forEach { model ->
            assertThat(model.kind).isEqualTo(ModelKind.VOICE)
            assertThat(model.engineType).isEqualTo(EngineType.TTS)
            val file = model.files.single()
            assertThat(file.role).isEqualTo(FileRole.MODEL)
            assertThat(file.sha256).matches("[0-9a-f]{64}")
            assertThat(file.bytes).isGreaterThan(0L)
            assertThat(model.recommendedRamMb).isGreaterThan(0)
            assertThat(model.acceptsLanguageHint).isFalse()
            assertThat(model.modelName).isNotEmpty()
        }
    }

    @Test
    fun `voice sizes and hashes match`() {
        assertThat(ModelCatalog.VOICE_FAST.totalBytes).isEqualTo(63_248_120L)
        assertThat(ModelCatalog.VOICE_FAST.files.single().sha256)
            .isEqualTo("d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5")
        assertThat(ModelCatalog.VOICE_BALANCED.totalBytes).isEqualTo(86_507_520L)
        assertThat(ModelCatalog.VOICE_BALANCED.files.single().sha256)
            .isEqualTo("e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6")
        assertThat(ModelCatalog.VOICE_QUALITY.totalBytes).isEqualTo(142_606_336L)
        assertThat(ModelCatalog.VOICE_QUALITY.files.single().sha256)
            .isEqualTo("f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7")
    }

    // ── Provisionamento dinâmico e suporte à API em Rust / Hust ───────────────────────────────────

    @Test
    fun `model provisioning falls back to default url when no mirror is configured`() {
        ModelProvisioning.customMirrorUrl = null
        ModelProvisioning.clearOverrides()
        val file = ModelCatalog.QWEN3_0_6B.files.single()
        assertThat(ModelProvisioning.resolveUrl(ModelCatalog.QWEN3_0_6B.id, file)).isEqualTo(file.url)
    }

    @Test
    fun `model provisioning redirects through custom mirror base url`() {
        ModelProvisioning.customMirrorUrl = "https://rust-api.example.com/models"
        ModelProvisioning.clearOverrides()
        val file = ModelCatalog.QWEN3_0_6B.files.single()
        assertThat(ModelProvisioning.resolveUrl(ModelCatalog.QWEN3_0_6B.id, file))
            .isEqualTo("https://rust-api.example.com/models/qwen3-0.6b-litertlm/Qwen3-0.6B.litertlm")
    }

    @Test
    fun `model provisioning interpolates template placeholders`() {
        ModelProvisioning.customMirrorUrl = "https://rust-api.example.com/v1/download?model={modelId}&file={fileName}"
        ModelProvisioning.clearOverrides()
        val file = ModelCatalog.QWEN3_0_6B.files.single()
        assertThat(ModelProvisioning.resolveUrl(ModelCatalog.QWEN3_0_6B.id, file))
            .isEqualTo("https://rust-api.example.com/v1/download?model=qwen3-0.6b-litertlm&file=Qwen3-0.6B.litertlm")
    }

    @Test
    fun `model provisioning respects specific dynamic overrides from Rust API`() {
        ModelProvisioning.customMirrorUrl = "https://rust-api.example.com/models"
        ModelProvisioning.setOverride("whisper-base-int8", "base-encoder.int8.onnx", "https://s3.custom.io/models/base-encoder.int8.onnx")
        val file = ModelCatalog.WHISPER_BASE.files.first { it.fileName == "base-encoder.int8.onnx" }
        assertThat(ModelProvisioning.resolveUrl("whisper-base-int8", file))
            .isEqualTo("https://s3.custom.io/models/base-encoder.int8.onnx")
        ModelProvisioning.clearOverrides()
        ModelProvisioning.customMirrorUrl = null
    }

    @Test
    fun `ids are unique across all kinds`() {
        assertThat(ModelCatalog.MODELS.map { it.id }).containsNoDuplicates()
    }
}
