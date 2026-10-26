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
    fun `text catalog has the four models, lightest first`() {
        // Só os cujo repositório, commit e SHA-256 existem de verdade na API do Hugging Face (2026-10-24 e 2026-10-25).
        assertThat(ModelCatalog.TEXT.map { it.id })
            .containsExactly("qwen3-0.6b-litertlm", "smollm2-1.7b-litertlm", "deepseek-r1-1.5b-litertlm", "gemma-4-e2b-litertlm")
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

    /**
     * Cada modelo de texto tem um nível em português como nome (o modelo de verdade vai na linha de baixo do cartão, em
     * `modelName`): nome sem o modelo dentro, que quebrava a linha e deixava "4)" sozinho a 360 dp.
     */
    @Test
    fun `text models are named by level, without the model inside the name`() {
        assertThat(ModelCatalog.TEXT.map { it.displayName }).containsExactly("Leve", "Intermediário", "Raciocínio", "Equilibrado").inOrder()
        assertThat(ModelCatalog.TEXT.map { it.displayName }).containsNoDuplicates()
        ModelCatalog.TEXT.forEach { assertThat(it.displayName).doesNotContain("(") }
        assertThat(ModelCatalog.TEXT.map { it.modelName })
            .containsExactly("Qwen3 0.6B", "SmolLM2 1.7B", "DeepSeek R1 1.5B", "Gemma 4 E2B")
            .inOrder()
    }

    /** RCAT-03: só baixável sem login, e fixado no commit — o SHA do catálogo tem de continuar valendo. */
    @Test
    fun `text urls point to a pinned huggingface commit and end with the file name`() {
        ModelCatalog.TEXT.flatMap { it.files }.forEach { file ->
            assertThat(file.url).matches("https://huggingface\\.co/litert-community/[^/]+/resolve/[0-9a-f]{40}/.+")
            assertThat(file.url).endsWith("/${file.fileName}")
        }
    }

    /**
     * Números lidos da API do Hugging Face em 2026-09-29 e reconferidos em 2026-10-24 (`/api/models/<repo>/tree/<commit>`).
     * SmolLM2 e DeepSeek: lidos em 2026-10-25 e o SHA-256 conferido baixando o arquivo inteiro.
     */
    @Test
    fun `text sizes and hashes match the upstream files`() {
        assertThat(ModelCatalog.QWEN3_0_6B.totalBytes).isEqualTo(614_236_160L)
        assertThat(ModelCatalog.QWEN3_0_6B.files.single().sha256)
            .isEqualTo("555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4")
        assertThat(ModelCatalog.SMOLLM2_1_7B.totalBytes).isEqualTo(1_730_949_280L)
        assertThat(ModelCatalog.SMOLLM2_1_7B.files.single().sha256)
            .isEqualTo("3c3e693157e2fb92b862b572cff74b7a353f81eb232cef682bf0fcc91855f8f1")
        assertThat(ModelCatalog.DEEPSEEK_R1_1_5B.totalBytes).isEqualTo(1_833_451_520L)
        assertThat(ModelCatalog.DEEPSEEK_R1_1_5B.files.single().sha256)
            .isEqualTo("69b35f01759eed765641ab4af589bbe98131fd2825662a086d9037409b8c1295")
        assertThat(ModelCatalog.GEMMA_4_E2B.totalBytes).isEqualTo(2_588_147_712L)
        assertThat(ModelCatalog.GEMMA_4_E2B.files.single().sha256)
            .isEqualTo("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c")
    }

    // ── Visão e voz: o que valer para qualquer entrada que entre nessas listas ─────────────────────────────────────
    // As entradas antigas tinham pins inventados e saíram (2026-10-24). Aqui só vão regras que valem para as futuras;
    // a conferência de pin está no `PinSanityTest` (forma) e no `scripts/verify-model-catalog.py` (API do HF).

    @Test
    fun `vision specs, when there are any, are vision kind with a model file`() {
        ModelCatalog.VISION.forEach { model ->
            assertThat(model.kind).isEqualTo(ModelKind.VISION)
            assertThat(model.files.map { it.role }).contains(FileRole.MODEL)
            assertThat(model.recommendedRamMb).isGreaterThan(0)
            assertThat(model.modelName).isNotEmpty()
        }
        assertThat(ModelCatalog.VISION.map { it.totalBytes }).isInOrder()
        assertThat(ModelCatalog.VISION.map { it.recommendedRamMb }).isInOrder()
    }

    @Test
    fun `voice specs, when there are any, are voice kind on the TTS engine with a model file`() {
        ModelCatalog.VOICE.forEach { model ->
            assertThat(model.kind).isEqualTo(ModelKind.VOICE)
            assertThat(model.engineType).isEqualTo(EngineType.TTS)
            assertThat(model.files.map { it.role }).contains(FileRole.MODEL)
            assertThat(model.recommendedRamMb).isGreaterThan(0)
            assertThat(model.modelName).isNotEmpty()
        }
        assertThat(ModelCatalog.VOICE.map { it.totalBytes }).isInOrder()
        assertThat(ModelCatalog.VOICE.map { it.recommendedRamMb }).isInOrder()
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
