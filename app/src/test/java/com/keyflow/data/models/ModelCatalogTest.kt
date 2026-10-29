package com.keyflow.data.models

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** CAT-01/02: integridade do catálogo embutido — o que o downloader e o engine assumem dele. */
class ModelCatalogTest {

    // Estas checagens são do catálogo de FALA (papéis do sherpa-onnx, repositórios csukuangfj); o de texto tem as suas.
    private val models = ModelCatalog.SPEECH
    private val releasedText = ModelCatalog.TEXT_ALL.filter { it.validatedOnDevice }

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
    fun `release keeps the seven proven models and debug is ordered by download size`() {
        // Só os cujo repositório, commit e SHA-256 existem de verdade na API do Hugging Face (2026-10-24, 2026-10-25 e 2026-10-26).
        assertThat(releasedText.map { it.id })
            .containsExactly(
                "qwen3-0.6b-litertlm", "qwen2.5-1.5b-litertlm", "smollm2-1.7b-litertlm", "deepseek-r1-1.5b-litertlm", "gemma-4-e2b-litertlm",
                "qwen3-4b-instruct-2507-litertlm", "gemma-4-e4b-litertlm",
            )
            .inOrder()
        assertThat(ModelCatalog.TEXT.map { it.totalBytes }).isInOrder()
    }

    /**
     * RAM recomendada, medida em 2026-10-27 (pico de VmRSS do `:reasoning`): Qwen3 1,66 GB, Gemma 4 E2B 1,72 GB e DeepSeek 1,73 GB no A32;
     * SmolLM2 3,3 GB no tablet de 6 GB; Gemma 4 E4B 4,4–4,5 GB no de 8 GB. A lista segue o tamanho do arquivo, mas a RAM do SmolLM2 (ativações
     * em FP32) passa a do DeepSeek e a do E2B — por isso a ordem de RAM não é a da lista. O Qwen3 4B (INT4, arquivo menor que o E4B) pede a maior RAM de
     * todas: na CPU o autor mede 4,6–6,1 GB (Galaxy S26) e no Vaio de 7,6 GB o processo passou de 5 GB e foi morto (RAM total exigida: 9831 MB).
     */
    @Test
    fun `recommended RAM follows the measured peaks`() {
        assertThat(releasedText.associate { it.id to it.recommendedRamMb })
            .containsExactly(
                "qwen3-0.6b-litertlm", 3072, "qwen2.5-1.5b-litertlm", 4096, "smollm2-1.7b-litertlm", 6144, "deepseek-r1-1.5b-litertlm", 4096,
                "gemma-4-e2b-litertlm", 4096, "qwen3-4b-instruct-2507-litertlm", 12288, "gemma-4-e4b-litertlm", 8192,
            )
        // O pior pico medido (em MB, no aparelho mais folgado) fica abaixo da RAM recomendada: a recomendação é de RAM total do aparelho.
        val peakMb = mapOf(
            ModelCatalog.QWEN3_0_6B to 1_946, ModelCatalog.DEEPSEEK_R1_1_5B to 2_560, ModelCatalog.GEMMA_4_E2B to 2_253,
            ModelCatalog.SMOLLM2_1_7B to 3_379, ModelCatalog.GEMMA_4_E4B to 4_608, ModelCatalog.QWEN2_5_1_5B to 2_400,
            // Não medido por nós na CPU de um aparelho que o rode: 6.093 MB é o maior RSS que o autor da conversão publica (CPU, Galaxy S26).
            ModelCatalog.QWEN3_4B to 6_093,
        )
        peakMb.forEach { (spec, peak) -> assertThat(peak).isLessThan(spec.recommendedRamMb) }
        assertThat(ModelCatalog.TEXT_ALL.maxBy { it.recommendedRamMb }).isSameInstanceAs(ModelCatalog.QWEN3_4B)
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
        assertThat(releasedText.map { it.displayName }).containsExactly("Leve", "Versátil", "Intermediário", "Raciocínio", "Equilibrado", "Potente", "Avançado").inOrder()
        assertThat(ModelCatalog.TEXT.map { it.displayName }).containsNoDuplicates()
        ModelCatalog.TEXT.forEach { assertThat(it.displayName).doesNotContain("(") }
        assertThat(releasedText.map { it.modelName })
            .containsExactly("Qwen3 0.6B", "Qwen2.5 1.5B", "SmolLM2 1.7B", "DeepSeek R1 1.5B", "Gemma 4 E2B", "Qwen3 4B Instruct 2507", "Gemma 4 E4B")
            .inOrder()
    }

    /**
     * Os modelos de texto responderam num aparelho real (decisão do Yan, 2026-10-27): o release lista todos. O Qwen3 4B respondeu na GPU do Vaio,
     * não na CPU que o app usa (ver o comentário do catálogo). O DeepSeek continua o único cujo prompt abre o `<think>`.
     */
    @Test
    fun `the validated models keep their order and the list shows every model in any build`() {
        assertThat(releasedText).containsExactly(
            ModelCatalog.QWEN3_0_6B, ModelCatalog.QWEN2_5_1_5B, ModelCatalog.SMOLLM2_1_7B,
            ModelCatalog.DEEPSEEK_R1_1_5B, ModelCatalog.GEMMA_4_E2B, ModelCatalog.QWEN3_4B, ModelCatalog.GEMMA_4_E4B,
        ).inOrder()
        assertThat(ModelCatalog.TEXT).containsExactlyElementsIn(ModelCatalog.TEXT_ALL).inOrder()
        assertThat(ModelCatalog.TEXT_ALL.filterNot { it.validatedOnDevice }).hasSize(6)
        assertThat(ModelCatalog.TEXT_ALL.filter { it.promptOpensThink }.map { it.id }).containsExactly("deepseek-r1-1.5b-litertlm", "nanbeige-4.2-3b-litertlm")
    }

    /** Decisão do Yan (2026-10-29): o release mostra tudo; `validatedOnDevice` é só informação e não esconde nenhuma aba. */
    @Test
    fun `every tab lists the unvalidated models too`() {
        assertThat(ModelCatalog.TEXT.filterNot { it.validatedOnDevice }).hasSize(6)
        assertThat(ModelCatalog.VISION).containsExactlyElementsIn(ModelCatalog.VISION_ALL)
        assertThat(ModelCatalog.VOICE).containsExactlyElementsIn(ModelCatalog.VOICE_ALL)
        assertThat((ModelCatalog.VISION + ModelCatalog.VOICE).none { it.validatedOnDevice }).isTrue()
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
        assertThat(ModelCatalog.QWEN3_0_6B.totalBytes).isEqualTo(613_722_264L)
        // Revisão fed81d17… (2026-10-27): Jinja com `enable_thinking`. O pin antigo, a3c5d805…, pensava sempre.
        assertThat(ModelCatalog.QWEN3_0_6B.files.single().url).contains("/fed81d17517e6ea64362e917ecf7e5accff12492/")
        assertThat(ModelCatalog.QWEN3_0_6B.files.single().sha256)
            .isEqualTo("c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108")
        // Qwen2.5 1.5B: lido em 2026-10-27 (`/api/models/litert-community/Qwen2.5-1.5B-Instruct/tree/2f23bacf…`) e o SHA-256 conferido baixando o arquivo inteiro.
        assertThat(ModelCatalog.QWEN2_5_1_5B.totalBytes).isEqualTo(1_597_922_856L)
        assertThat(ModelCatalog.QWEN2_5_1_5B.files.single().sha256)
            .isEqualTo("c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e")
        assertThat(ModelCatalog.QWEN2_5_1_5B.files.single().url).contains("/2f23bacf8ec78b83dfcd7271c5cb25777b1be5e0/")
        assertThat(ModelCatalog.SMOLLM2_1_7B.totalBytes).isEqualTo(1_730_949_280L)
        assertThat(ModelCatalog.SMOLLM2_1_7B.files.single().sha256)
            .isEqualTo("3c3e693157e2fb92b862b572cff74b7a353f81eb232cef682bf0fcc91855f8f1")
        assertThat(ModelCatalog.DEEPSEEK_R1_1_5B.totalBytes).isEqualTo(1_833_451_520L)
        assertThat(ModelCatalog.DEEPSEEK_R1_1_5B.files.single().sha256)
            .isEqualTo("69b35f01759eed765641ab4af589bbe98131fd2825662a086d9037409b8c1295")
        assertThat(ModelCatalog.GEMMA_4_E2B.totalBytes).isEqualTo(2_588_147_712L)
        assertThat(ModelCatalog.GEMMA_4_E2B.files.single().sha256)
            .isEqualTo("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c")
        // E4B: lido em 2026-10-26 (`/api/models/litert-community/gemma-4-E4B-it-litert-lm/tree/e6a3892a…`) e o SHA-256 conferido baixando o arquivo inteiro.
        assertThat(ModelCatalog.GEMMA_4_E4B.totalBytes).isEqualTo(3_659_530_240L)
        assertThat(ModelCatalog.GEMMA_4_E4B.files.single().sha256)
            .isEqualTo("0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0")
        // Qwen3 4B Instruct-2507: lido em 2026-10-27 (`/api/models/litert-community/Qwen3-4B-Instruct-2507/tree/4d409771…`) e o SHA-256 conferido baixando o arquivo inteiro.
        assertThat(ModelCatalog.QWEN3_4B.totalBytes).isEqualTo(2_659_057_664L)
        assertThat(ModelCatalog.QWEN3_4B.files.single().sha256)
            .isEqualTo("9e48b165836256f5344d9d044930607b9c47f6ef34e27f82e96881664f3ba2fd")
        assertThat(ModelCatalog.QWEN3_4B.files.single().url).contains("/4d409771b414d86b270ca54d27ffc45032a81142/")
    }

    // ── Visão e voz: o que valer para qualquer entrada que entre nessas listas ─────────────────────────────────────
    // As entradas antigas tinham pins inventados e saíram (2026-10-24). Aqui só vão regras que valem para as futuras;
    // a conferência de pin está no `PinSanityTest` (forma) e no `scripts/verify-model-catalog.py` (API do HF).

    @Test
    fun `vision specs are vision kind on the OCR engine with detector, recognizer and dictionary`() {
        ModelCatalog.VISION.forEach { model ->
            assertThat(model.kind).isEqualTo(ModelKind.VISION)
            assertThat(model.engineType).isEqualTo(EngineType.OCR)
            assertThat(model.files.map { it.role }).containsExactly(FileRole.DETECTOR, FileRole.RECOGNIZER, FileRole.DICTIONARY).inOrder()
            assertThat(model.recommendedRamMb).isGreaterThan(0)
            assertThat(model.modelName).isNotEmpty()
        }
        assertThat(ModelCatalog.VISION.map { it.totalBytes }).isInOrder()
        assertThat(ModelCatalog.VISION.map { it.recommendedRamMb }).isInOrder()
    }

    /**
     * VC-T2a: o OCR é o par do `monkt/paddleocr-onnx` no commit conferido em 2026-10-27 (tamanhos e SHA-256 do `lfs.oid` da API do HF e
     * recalculados baixando os arquivos). Os cinco pins inventados antigos não voltam: o catálogo de visão tem só esta entrada.
     */
    @Test
    fun `the vision catalog is the paddleocr pair pinned at the verified commit`() {
        assertThat(ModelCatalog.VISION_ALL.map { it.id }).containsExactly("ocr-paddle-latin")
        assertThat(ModelCatalog.VISION).containsExactly(ModelCatalog.OCR_PADDLE_LATIN) // o teste roda no debug
        assertThat(ModelCatalog.byId("ocr-paddle-latin")).isSameInstanceAs(ModelCatalog.OCR_PADDLE_LATIN)
        val ocr = ModelCatalog.OCR_PADDLE_LATIN
        val repo = "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65"
        assertThat(ocr.file(FileRole.DETECTOR)!!.url).isEqualTo("$repo/detection/v3/det.onnx")
        assertThat(ocr.file(FileRole.RECOGNIZER)!!.url).isEqualTo("$repo/languages/latin/rec.onnx")
        assertThat(ocr.file(FileRole.DICTIONARY)!!.url).isEqualTo("$repo/languages/latin/dict.txt")
        assertThat(ocr.files.map { it.bytes }).containsExactly(2_429_873L, 7_862_832L, 1_634L).inOrder()
        assertThat(ocr.totalBytes).isEqualTo(10_294_339L)
        assertThat(ocr.files.map { it.sha256 }).containsExactly(
            "ee40e80071ba3a320d4efda75f3e22047a7d049e9bf7bcaaf9daea23fc21b935",
            "614ffc2d6d3902d360fad7f1b0dd455ee45e877069d14c4e51a99dc4ef144409",
            "3c0a8a79b612653c25f765271714f71281e4e955962c153e272b7b8c1d2b13ff",
        ).inOrder()
        ocr.files.forEach { assertThat(it.url).endsWith("/${it.fileName}") }
    }

    /** O OCR ainda não foi provado num aparelho (VC-T6), mas a aba Visão o lista em todos os builds. */
    @Test
    fun `the ocr model is listed even though it is not validated on a device`() {
        assertThat(ModelCatalog.OCR_PADDLE_LATIN.validatedOnDevice).isFalse()
        assertThat(ModelCatalog.VISION).containsExactlyElementsIn(ModelCatalog.VISION_ALL)
    }

    /** FAL-T8/T10: VITS (mais leve) e Supertonic 3, pins reais. A aba Voz lista os dois em todos os builds. */
    @Test
    fun `voice specs are the vits portuguese entry and the official supertonic`() {
        assertThat(ModelCatalog.VOICE_ALL.map { it.id }).containsExactly("vits-coqui-pt", "supertonic-3").inOrder()
        assertThat(ModelCatalog.VOICE.map { it.id }).containsExactly("vits-coqui-pt", "supertonic-3").inOrder()
        val vits = ModelCatalog.VITS_COQUI_PT
        val supertonic = ModelCatalog.SUPERTONIC_3
        listOf(vits, supertonic).forEach { model ->
            assertThat(model.kind).isEqualTo(ModelKind.VOICE)
            assertThat(model.engineType).isEqualTo(EngineType.TTS)
            assertThat(model.recommendedRamMb).isGreaterThan(0)
            assertThat(model.modelName).isNotEmpty()
            assertThat(model.validatedOnDevice).isFalse()
            assertThat(model.files.map { it.fileName }.joinToString(" ")).doesNotContain("int8")
            assertThat(model.id).doesNotContain("pt-BR")
            assertThat(model.displayName).doesNotContain("pt-BR")
        }
        assertThat(vits.displayName).isEqualTo("português")
        assertThat(vits.vendor).isEqualTo(ModelVendor.COQUI)
        assertThat(vits.files.map { it.role }).containsExactly(FileRole.MODEL, FileRole.TOKENS).inOrder()
        assertThat(vits.file(FileRole.MODEL)!!.sha256).isEqualTo("fcc22bb219ccda42df47546d0e9ea7b078b9c5a2ebf90ef7ec589c995ecbc736")
        assertThat(vits.file(FileRole.MODEL)!!.bytes).isEqualTo(71_024_123L)
        assertThat(vits.file(FileRole.TOKENS)!!.sha256).isEqualTo("4b4ba3385bf661b87735835de24365e25623d250be0621bc184dff050df4c2fb")
        assertThat(vits.file(FileRole.TOKENS)!!.bytes).isEqualTo(1_410L)
        assertThat(supertonic.vendor).isEqualTo(ModelVendor.SUPERTONE)
        assertThat(supertonic.files.map { it.role }).containsExactly(
            FileRole.DURATION, FileRole.TEXT_ENCODER, FileRole.VECTOR_ESTIMATOR, FileRole.VOCODER,
            FileRole.UNICODE_INDEX, FileRole.TTS_CONFIG, FileRole.VOICE_STYLE,
        ).inOrder()
        assertThat(supertonic.file(FileRole.VECTOR_ESTIMATOR)!!.bytes).isEqualTo(256_534_781L)
        assertThat(supertonic.files.map { it.fileName }).doesNotContain("int8")
        assertThat(ModelCatalog.VOICE_ALL.map { it.id }.joinToString(" ")).doesNotContain("kokoro")
        assertThat(ModelCatalog.VOICE_ALL.map { it.id }.joinToString(" ")).doesNotContain("piper")
        assertThat(ModelCatalog.VOICE.map { it.totalBytes }).isInOrder()
        assertThat(ModelCatalog.VOICE.map { it.recommendedRamMb }).isInOrder()
        assertThat(vits.totalBytes).isLessThan(supertonic.totalBytes)
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

    @Test
    fun `every local model names the company that makes it, for the card logo`() {
        val without = ModelCatalog.MODELS.filter { it.vendor == null }.map { it.id }
        assertThat(without).isEmpty()
    }

    @Test
    fun `vendors match the model, not the host that serves the file`() {
        val byId = ModelCatalog.MODELS.associate { it.id to it.vendor }
        assertThat(byId).containsEntry("whisper-base-int8", ModelVendor.OPENAI)
        assertThat(byId).containsEntry("whisper-small-int8", ModelVendor.OPENAI)
        assertThat(byId).containsEntry("parakeet-tdt-0.6b-v3-int8", ModelVendor.NVIDIA)
        assertThat(byId).containsEntry("qwen3-0.6b-litertlm", ModelVendor.QWEN)
        assertThat(byId).containsEntry("qwen3-4b-instruct-2507-litertlm", ModelVendor.QWEN)
        assertThat(byId).containsEntry("smollm2-1.7b-litertlm", ModelVendor.HUGGING_FACE)
        assertThat(byId).containsEntry("deepseek-r1-1.5b-litertlm", ModelVendor.DEEPSEEK)
        assertThat(byId).containsEntry("gemma-4-e2b-litertlm", ModelVendor.GOOGLE)
        assertThat(byId).containsEntry("gemma-4-e4b-litertlm", ModelVendor.GOOGLE)
        assertThat(byId).containsEntry("granite-4.2-3b-litertlm", ModelVendor.IBM)
        assertThat(byId).containsEntry("nanbeige-4.2-3b-litertlm", ModelVendor.NANBEIGE)
        // PaddleOCR é da PaddlePaddle (Baidu), embora os arquivos ONNX venham de um repositório de terceiros no Hugging Face.
        assertThat(byId).containsEntry("ocr-paddle-latin", ModelVendor.PADDLEPADDLE)
    }

    /**
     * Aviso de modo pensamento no card. Fontes: Qwen3, card do `Qwen/Qwen3-0.6B` ("seamless switching between thinking mode and non-thinking
     * mode"); DeepSeek R1, o prompt abre o `<think>` sempre; Gemma 4, card do `google/gemma-4-E2B-it` e do E4B ("configurable thinking modes",
     * liga com `<|think|>` no prompt de sistema); Qwen2.5, SmolLM2 e Qwen3 4B Instruct-2507 são modelos de instrução comuns (o card do 2507 diz
     * "supports only non-thinking mode").
     */
    @Test
    fun `text models declare whether they think`() {
        val byId = ModelCatalog.TEXT_ALL.associate { it.id to it.thinking }
        assertThat(byId).containsExactly(
            "qwen3-0.6b-litertlm", ThinkingSupport.OPTIONAL,
            "qwen2.5-1.5b-litertlm", ThinkingSupport.NONE,
            "smollm2-1.7b-litertlm", ThinkingSupport.NONE,
            "deepseek-r1-1.5b-litertlm", ThinkingSupport.ALWAYS,
            "gemma-4-e2b-litertlm", ThinkingSupport.OPTIONAL,
            "qwen3-4b-instruct-2507-litertlm", ThinkingSupport.NONE,
            "gemma-4-e4b-litertlm", ThinkingSupport.OPTIONAL,
            "functiongemma-270m-mobile-actions-litertlm", ThinkingSupport.NONE,
            "qwen2.5-coder-1.5b-litertlm", ThinkingSupport.NONE,
            "smollm3-3b-litertlm", ThinkingSupport.OPTIONAL,
            "granite-4.2-3b-litertlm", ThinkingSupport.OPTIONAL,
            "nanbeige-4.2-3b-litertlm", ThinkingSupport.ALWAYS,
            "qwen3.5-4b-litertlm", ThinkingSupport.OPTIONAL,
        )
        // O modelo que abre o `<think>` no prompt é, por definição, o que sempre pensa.
        assertThat(ModelCatalog.TEXT_ALL.filter { it.promptOpensThink }.map { it.thinking }.toSet()).containsExactly(ThinkingSupport.ALWAYS)
        assertThat(ModelCatalog.SPEECH.map { it.thinking }.toSet()).containsExactly(ThinkingSupport.NONE)
    }

    /**
     * Etiquetas de capacidade do card: só o que o fabricante declara, com a fonte no comentário de cada entrada. "Ferramentas": Qwen3 0.6B (card, "Agentic
     * Use"), Qwen2.5 1.5B (chat template com `tools`/`<tool_call>`), SmolLM2 1.7B (card, "function calling") e os dois Gemma 4 (doc oficial "Function
     * calling with Gemma 4" e card); o DeepSeek R1 Distill não declara, fica sem a etiqueta. Os Gemma 4 declaram também a visão (ler texto, descrever
     * imagem, responder sobre a tela — card multimodal e seções `tf_lite_vision_*` no `.litertlm`, VC-T2c); o que falta provar no aparelho é carregar
     * com visão (VC-T15), e por isso a aba Visão só os lista no debug (ver `visionModels`). Parakeet: português entre os idiomas de treino.
     */
    @Test
    fun `capabilities declare only what the maker states`() {
        val tools = setOf(ModelCapability.TOOLS)
        val toolsAndVision = tools + ModelCatalog.VISION_CAPABILITIES
        val byId = ModelCatalog.MODELS.associate { it.id to it.capabilities }
        assertThat(byId).containsExactly(
            "whisper-base-int8", emptySet<ModelCapability>(),
            "whisper-small-int8", emptySet<ModelCapability>(),
            "parakeet-tdt-0.6b-v3-int8", setOf(ModelCapability.NATIVE_PORTUGUESE),
            "qwen3-0.6b-litertlm", tools,
            "qwen2.5-1.5b-litertlm", tools,
            "qwen3-4b-instruct-2507-litertlm", tools,
            "smollm2-1.7b-litertlm", tools,
            "deepseek-r1-1.5b-litertlm", emptySet<ModelCapability>(),
            "gemma-4-e2b-litertlm", toolsAndVision,
            "gemma-4-e4b-litertlm", toolsAndVision,
            "ocr-paddle-latin", setOf(ModelCapability.READ_TEXT),
            "functiongemma-270m-mobile-actions-litertlm", emptySet<ModelCapability>(),
            "qwen2.5-coder-1.5b-litertlm", emptySet<ModelCapability>(),
            "smollm3-3b-litertlm", emptySet<ModelCapability>(),
            "granite-4.2-3b-litertlm", emptySet<ModelCapability>(),
            "nanbeige-4.2-3b-litertlm", emptySet<ModelCapability>(),
            "qwen3.5-4b-litertlm", emptySet<ModelCapability>(),
            "vits-coqui-pt", emptySet<ModelCapability>(),
            "supertonic-3", emptySet<ModelCapability>(),
        )
        // Visão só nos que a declaram: o OCR (ler texto) e os dois Gemma 4.
        val seeing = ModelCatalog.MODELS.filter { m -> m.capabilities.any { it in ModelCatalog.VISION_CAPABILITIES } }.map { it.id }
        assertThat(seeing).containsExactly("gemma-4-e2b-litertlm", "gemma-4-e4b-litertlm", "ocr-paddle-latin")
        assertThat(ModelCapability.entries.map { it.name })
            .containsExactly("READ_TEXT", "DESCRIBE_IMAGE", "ANSWER_ABOUT_SCREEN", "TOOLS", "NATIVE_PORTUGUESE").inOrder()
    }

    // ── VC-T2c / VC-T11: o que a aba Visão lista ─────────────────────────────────────────────────────────────────

    /** O OCR e os Gemma 4 (mesmos objetos do catálogo de texto), do menor ao maior arquivo; nada de fala nem de texto sem visão. */
    @Test
    fun `the vision list is the ocr plus the Gemma 4 that declare vision, smallest file first`() {
        val vision = ModelCatalog.visionModels()
        assertThat(vision.map { it.id }).containsExactly("ocr-paddle-latin", "gemma-4-e2b-litertlm", "gemma-4-e4b-litertlm").inOrder()
        assertThat(vision.map { it.totalBytes }).isInOrder()
        // Uma entrada, uma cópia em disco: o Gemma da Visão É o do Raciocínio (mesmo objeto, mesmo id, mesmo tipo, mesmos arquivos).
        assertThat(vision[1]).isSameInstanceAs(ModelCatalog.GEMMA_4_E2B)
        assertThat(vision[2]).isSameInstanceAs(ModelCatalog.GEMMA_4_E4B)
        listOf(ModelCatalog.GEMMA_4_E2B, ModelCatalog.GEMMA_4_E4B).forEach {
            assertThat(it.kind).isEqualTo(ModelKind.TEXT)
            assertThat(ModelCatalog.TEXT).contains(it)
            assertThat(it.capabilities).containsAtLeastElementsIn(ModelCatalog.VISION_CAPABILITIES)
        }
        assertThat(ModelCatalog.OCR_PADDLE_LATIN.capabilities).containsExactly(ModelCapability.READ_TEXT)
        assertThat(ModelCatalog.SPEECH.map { it.id } + ModelCatalog.QWEN3_0_6B.id).containsNoneIn(vision.map { it.id })
    }

    /** Sem filtro por build: a lista da Visão com o catálogo todo traz o OCR e os dois Gemma, validados ou não. */
    @Test
    fun `the vision list over the whole catalog has the ocr and both Gemma`() {
        assertThat(ModelCatalog.visionModels(ModelCatalog.MODELS).map { it.id })
            .containsExactly("ocr-paddle-latin", "gemma-4-e2b-litertlm", "gemma-4-e4b-litertlm").inOrder()
    }

    @Test
    fun `a model that declares vision joins the list and one that does not stays out`() {
        val seeing = ModelCatalog.QWEN3_0_6B.copy(id = "remoto-visao", capabilities = setOf(ModelCapability.DESCRIBE_IMAGE))
        val blind = ModelCatalog.QWEN3_0_6B.copy(id = "remoto-texto", capabilities = setOf(ModelCapability.TOOLS))
        assertThat(ModelCatalog.visionModels(listOf(blind, seeing, ModelCatalog.WHISPER_BASE)).map { it.id })
            .containsExactly("remoto-visao")
        // Validado ou não, quem declara visão entra.
        assertThat(ModelCatalog.visionModels(listOf(seeing.copy(validatedOnDevice = false))).map { it.id }).containsExactly("remoto-visao")
    }

    /**
     * Trava de versão do catálogo: o manifesto remoto só vale se `catalogVersion >= EMBEDDED_VERSION`, então mexer num pin sem subir a versão deixaria um
     * manifesto/cache antigo reverter o pin. Mudou um pin ou entrou modelo: suba `ModelCatalog.EMBEDDED_VERSION` e troque a impressão digital abaixo.
     */
    @Test
    fun `as capacidades de visão invalidam a versão anterior do catálogo`() {
        assertThat(ModelCatalog.EMBEDDED_VERSION).isGreaterThan(2026102802)
    }

    @Test
    fun `changing a catalog pin requires bumping the embedded catalog version`() {
        assertThat(ModelCatalog.EMBEDDED_VERSION).isEqualTo(2026102901)
        assertThat(ModelCatalog.pinsFingerprint()).isEqualTo(PINS_FINGERPRINT_2026102901)
    }

    private companion object {
        const val PINS_FINGERPRINT_2026102901 = "49f7380fc950ed2f88a1dc26a08dcfa68f8526fbc8f45c90f44adaa2b4ff9191"
    }
}
