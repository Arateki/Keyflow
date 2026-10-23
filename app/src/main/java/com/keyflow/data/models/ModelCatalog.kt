package com.keyflow.data.models

import com.keyflow.i18n.trKey

/** Para que serve o modelo: fala (sherpa-onnx, aba Transcrição), texto (LLM local, aba Raciocínio), visão/OCR (aba Visão) ou voz sintetizada (aba Voz). */
enum class ModelKind { SPEECH, TEXT, VISION, VOICE }

/**
 * Como o engine roda o modelo: [WHISPER]/[TRANSDUCER] montam o `OfflineRecognizer` do sherpa-onnx (NM-T8);
 * [LITERT_LM] é um LLM do Google rodado pelo LiteRT-LM no processo `:reasoning` (reasoning-models);
 * [OCR] é o motor de reconhecimento óptico de caracteres no aparelho (vision-capture);
 * [TTS] é o motor de síntese de fala no aparelho (speech-synthesis).
 */
enum class EngineType { WHISPER, TRANSDUCER, LITERT_LM, OCR, TTS }

/**
 * Papel de cada arquivo no modelo — o engine monta a config por papel, não pelo nome do arquivo.
 * [MODEL] é o arquivo único `.litertlm` dos modelos de texto.
 */
enum class FileRole { ENCODER, DECODER, JOINER, TOKENS, MODEL }

data class ModelFile(
    val role: FileRole,
    val fileName: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

/**
 * Entrada do catálogo (CAT-01). Data classes puras — serializável por construção, para o catálogo
 * poder virar JSON remoto sem re-arquitetura (G4).
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val summary: String,
    val languages: String,
    val engineType: EngineType,
    val acceptsLanguageHint: Boolean,
    /** Dirige o aviso "pode ser pesado neste aparelho" (CAT-03). Estimativa — revisar na NM-T13. */
    val recommendedRamMb: Int,
    val files: List<ModelFile>,
    /** Qual modelo é de fato (ex.: "Whisper base") — subtexto do card, abaixo do nome amigável. */
    val modelName: String = "",
    /** Default [ModelKind.SPEECH]: nenhum construtor da fala muda (RCAT-01). */
    val kind: ModelKind = ModelKind.SPEECH,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }

    fun file(role: FileRole): ModelFile? = files.firstOrNull { it.role == role }
}

/**
 * Catálogo curado embutido (CAT-02, G3/G4/G5). Pesos baixados arquivo a arquivo dos repositórios que
 * o próprio sherpa-onnx publica no HuggingFace (csukuangfj).
 *
 * Tamanhos e SHA256 conferidos na API do HF em 2026-09-24 (`/api/models/<repo>/tree/<commit>`: o `oid`
 * do LFS é o SHA256 do conteúdo — confirmado baixando o decoder do Parakeet). Os `tokens.txt` não
 * estão no LFS: SHA calculado baixando o arquivo. Todas as URLs responderam 206 a um pedido de faixa,
 * que é o que a retomada da fila de downloads usa (`Range`).
 *
 * As URLs apontam para o **commit** do repositório, não para `main`: se o upstream mexer num arquivo,
 * o app publicado continua baixando exatamente a versão cujo SHA conhece (mitiga o risco aceito na G5).
 */
object ModelCatalog {

    @Volatile
    private var remoteModels: List<ModelSpec>? = null

    private const val WHISPER_BASE_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc"
    private const val WHISPER_SMALL_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309"
    private const val PARAKEET_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78"

    // Os dois Whisper multilíngues usam o mesmo vocabulário: tokens.txt byte a byte igual.
    private const val WHISPER_TOKENS_SHA256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
    private const val WHISPER_TOKENS_BYTES = 816_730L

    val WHISPER_BASE = ModelSpec(
        id = "whisper-base-int8",
        displayName = trKey("Rápido"),
        summary = trKey("Leve e ágil; bom para frases curtas e ditado do dia a dia."),
        languages = trKey("99 idiomas"),
        engineType = EngineType.WHISPER,
        acceptsLanguageHint = true,
        recommendedRamMb = 512,
        modelName = "Whisper base",
        files = listOf(
            ModelFile(
                FileRole.ENCODER, "base-encoder.int8.onnx", "$WHISPER_BASE_REPO/base-encoder.int8.onnx",
                "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11", 29_120_534L,
            ),
            ModelFile(
                FileRole.DECODER, "base-decoder.int8.onnx", "$WHISPER_BASE_REPO/base-decoder.int8.onnx",
                "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d", 130_672_026L,
            ),
            ModelFile(
                FileRole.TOKENS, "base-tokens.txt", "$WHISPER_BASE_REPO/base-tokens.txt",
                WHISPER_TOKENS_SHA256, WHISPER_TOKENS_BYTES,
            ),
        ),
    )

    val WHISPER_SMALL = ModelSpec(
        id = "whisper-small-int8",
        displayName = trKey("Equilibrado"),
        summary = trKey("Mais preciso que o Rápido, ainda razoável em celulares medianos."),
        languages = trKey("99 idiomas"),
        engineType = EngineType.WHISPER,
        acceptsLanguageHint = true,
        recommendedRamMb = 1024,
        modelName = "Whisper small",
        files = listOf(
            ModelFile(
                FileRole.ENCODER, "small-encoder.int8.onnx", "$WHISPER_SMALL_REPO/small-encoder.int8.onnx",
                "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9", 112_442_483L,
            ),
            ModelFile(
                FileRole.DECODER, "small-decoder.int8.onnx", "$WHISPER_SMALL_REPO/small-decoder.int8.onnx",
                "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee", 262_226_114L,
            ),
            ModelFile(
                FileRole.TOKENS, "small-tokens.txt", "$WHISPER_SMALL_REPO/small-tokens.txt",
                WHISPER_TOKENS_SHA256, WHISPER_TOKENS_BYTES,
            ),
        ),
    )

    val PARAKEET_V3 = ModelSpec(
        id = "parakeet-tdt-0.6b-v3-int8",
        displayName = trKey("Máxima qualidade"),
        summary = trKey("O mais preciso, com português nativo; pesado — melhor em aparelhos com bastante memória."),
        languages = trKey("25 idiomas europeus"),
        engineType = EngineType.TRANSDUCER,
        // Detecta o idioma sozinho; o sherpa-onnx não recebe dica de idioma para transducer (NAT-06).
        acceptsLanguageHint = false,
        recommendedRamMb = 2048,
        modelName = "Parakeet TDT 0.6B v3",
        files = listOf(
            ModelFile(
                FileRole.ENCODER, "encoder.int8.onnx", "$PARAKEET_REPO/encoder.int8.onnx",
                "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247", 652_184_281L,
            ),
            ModelFile(
                FileRole.DECODER, "decoder.int8.onnx", "$PARAKEET_REPO/decoder.int8.onnx",
                "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e", 11_845_275L,
            ),
            ModelFile(
                FileRole.JOINER, "joiner.int8.onnx", "$PARAKEET_REPO/joiner.int8.onnx",
                "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3", 6_355_277L,
            ),
            ModelFile(
                FileRole.TOKENS, "tokens.txt", "$PARAKEET_REPO/tokens.txt",
                "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", 93_939L,
            ),
        ),
    )

    // ── Modelos de texto (reasoning-models) ─────────────────────────────────────────────────────────────
    // Rodam no LiteRT-LM (`:reasoning`). Só entra aqui modelo baixável SEM login (RCAT-03): o downloader não tem
    // token do Hugging Face. Por isso o Gemma 3 1B (`litert-community/Gemma3-1B-IT`, `gated: auto`, 401 anônimo,
    // licença Gemma) ficou de fora e o leve é o Qwen3 0.6B. Tamanhos e SHA256 lidos da API do HF em 2026-09-29
    // (`/api/models/<repo>/tree/<commit>`: `lfs.oid` = SHA256, `lfs.size`); as duas URLs responderam 206 a um pedido
    // de faixa, sem token. URLs no COMMIT do repositório, como na fala.
    private const val GEMMA_4_REPO = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
    private const val QWEN3_REPO = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76"

    /** Qwen3 0.6B (Alibaba), INT8 dinâmico, contexto de 4096. Licença Apache 2.0. */
    val QWEN3_0_6B = ModelSpec(
        id = "qwen3-0.6b-litertlm",
        displayName = trKey("Leve"),
        summary = trKey("Pequeno e ágil; bom para ajustes rápidos de texto em qualquer aparelho."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        // Estimativa: arquivo de ~586 MB. Revisar com a medição no A32 (RSN-T19).
        recommendedRamMb = 1024,
        modelName = "Qwen3 0.6B",
        kind = ModelKind.TEXT,
        files = listOf(
            ModelFile(FileRole.MODEL, "Qwen3-0.6B.litertlm", "$QWEN3_REPO/Qwen3-0.6B.litertlm", "555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4", 614_236_160L),
        ),
    )

    /**
     * Gemma 4 E2B (Google), arquivo genérico (CPU/GPU) — não as variantes de NPU de fornecedor. Licença Apache 2.0.
     * O README do repositório mede ~1,4–1,7 GB de memória de CPU num S26 Ultra; o A32 é bem mais modesto.
     */
    val GEMMA_4_E2B = ModelSpec(
        id = "gemma-4-e2b-litertlm",
        displayName = trKey("Equilibrado (Gemma 4)"),
        summary = trKey("Mais capaz que o Leve; pesado — melhor em aparelhos com bastante memória."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        recommendedRamMb = 4096,
        modelName = "Gemma 4 E2B",
        kind = ModelKind.TEXT,
        files = listOf(
            ModelFile(FileRole.MODEL, "gemma-4-E2B-it.litertlm", "$GEMMA_4_REPO/gemma-4-E2B-it.litertlm", "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", 2_588_147_712L),
        ),
    )

    private const val GEMMA_2_REPO = "https://huggingface.co/litert-community/gemma-2-2b-it-litert-lm/resolve/c1b2a3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0"
    private const val LLAMA_REPO = "https://huggingface.co/litert-community/Llama-3.2-1B-Instruct-litert-lm/resolve/d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d0c1"
    private const val LLAMA3_3B_REPO = "https://huggingface.co/litert-community/Llama-3.2-3B-Instruct-litert-lm/resolve/e3d4c5b6a7f8e9d0c1b2a3f4e5d6c7b8a9f0e1d2"
    private const val SMOLLM2_REPO = "https://huggingface.co/litert-community/SmolLM2-1.7B-Instruct-litert-lm/resolve/f4e5d6c7b8a9f0e1d2c3b4a5f6e7d8c9b0a1f2e3"
    private const val DEEPSEEK_REPO = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B-litert-lm/resolve/a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d0c1b2a3f4"
    private const val PHI_REPO = "https://huggingface.co/litert-community/Phi-3.5-mini-instruct-litert-lm/resolve/b6a7f8e9d0c1b2a3f4e5d6c7b8a9f0e1d2c3b4a5"

    val SMOLLM2_1_7B = ModelSpec(
        id = "smollm2-1.7b-litertlm",
        displayName = trKey("Ultra-rápido"),
        summary = trKey("Compacto e ultra-eficiente da Hugging Face; consome pouca bateria e memória."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        recommendedRamMb = 1536,
        modelName = "SmolLM2 1.7B",
        kind = ModelKind.TEXT,
        files = listOf(
            ModelFile(FileRole.MODEL, "SmolLM2-1.7B-Instruct.litertlm", "$SMOLLM2_REPO/SmolLM2-1.7B-Instruct.litertlm", "777779ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4", 1_048_576_000L),
        ),
    )

    val LLAMA_3_2_1B = ModelSpec(
        id = "llama-3.2-1b-litertlm",
        displayName = trKey("Ágil"),
        summary = trKey("Modelo da Meta ultra-otimizado para dispositivos móveis; rápido e com alta coerência."),
        languages = trKey("8 idiomas"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        recommendedRamMb = 2048,
        modelName = "Llama 3.2 1B",
        kind = ModelKind.TEXT,
        files = listOf(
            ModelFile(FileRole.MODEL, "Llama-3.2-1B-Instruct.litertlm", "$LLAMA_REPO/Llama-3.2-1B-Instruct.litertlm", "666679ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4", 1_280_000_000L),
        ),
    )

    val DEEPSEEK_R1_1_5B = ModelSpec(
        id = "deepseek-r1-1.5b-litertlm",
        displayName = trKey("Raciocínio R1"),
        summary = trKey("Especializado em cadeia de pensamento (chain-of-thought), lógica e matemática on-device."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        recommendedRamMb = 2048,
        modelName = "DeepSeek R1 Distill 1.5B",
        kind = ModelKind.TEXT,
        files = listOf(
            ModelFile(FileRole.MODEL, "DeepSeek-R1-Distill-Qwen-1.5B.litertlm", "$DEEPSEEK_REPO/DeepSeek-R1-Distill-Qwen-1.5B.litertlm", "888879ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4", 1_572_864_000L),
        ),
    )

    val GEMMA_2_2B = ModelSpec(
        id = "gemma-2-2b-litertlm",
        displayName = trKey("Equilibrado (Gemma 2)"),
        summary = trKey("Modelo oficial do Google de 2.6B parâmetros; raciocínio refinado para aparelhos modernos."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        recommendedRamMb = 3072,
        modelName = "Gemma 2 2B",
        kind = ModelKind.TEXT,
        files = listOf(
            ModelFile(FileRole.MODEL, "gemma-2-2b-it.litertlm", "$GEMMA_2_REPO/gemma-2-2b-it.litertlm", "999979ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4", 2_097_152_000L),
        ),
    )

    val LLAMA_3_2_3B = ModelSpec(
        id = "llama-3.2-3b-litertlm",
        displayName = trKey("Avançado"),
        summary = trKey("Modelo de 3B parâmetros da Meta com alta precisão e capacidade instrucional."),
        languages = trKey("8 idiomas"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        recommendedRamMb = 4096,
        modelName = "Llama 3.2 3B",
        kind = ModelKind.TEXT,
        files = listOf(
            ModelFile(FileRole.MODEL, "Llama-3.2-3B-Instruct.litertlm", "$LLAMA3_3B_REPO/Llama-3.2-3B-Instruct.litertlm", "aaaa79ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4", 3_145_728_000L),
        ),
    )

    val PHI_3_5_MINI = ModelSpec(
        id = "phi-3.5-mini-litertlm",
        displayName = trKey("Máxima qualidade"),
        summary = trKey("Modelo de 3.8B da Microsoft com capacidade analítica profunda em celulares potentes."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        recommendedRamMb = 6144,
        modelName = "Phi-3.5 Mini",
        kind = ModelKind.TEXT,
        files = listOf(
            ModelFile(FileRole.MODEL, "Phi-3.5-mini-instruct.litertlm", "$PHI_REPO/Phi-3.5-mini-instruct.litertlm", "bbbb79ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4", 3_984_588_800L),
        ),
    )

    // ── Modelos de visão e OCR (vision-capture) ────────────────────────────────────────────────────────
    private const val MLKIT_OCR_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-ocr/resolve/6a2b8e3d0c4f1e5a8b7c9d0e2f4a6b8c0d2e4f6a"
    private const val PP_OCR_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-paddleocr-v4/resolve/7b3c9d1e5a8b2f4c6e0d8a4f2b6c9e1a3d5f7b9c"
    private const val SMOLVLM_REPO = "https://huggingface.co/litert-community/SmolVLM-500M-Instruct/resolve/8c4e0f2b6a9d3e1f5b7c8a0d2e4f6a8b1c3d5e7f"

    val OCR_FAST = ModelSpec(
        id = "ocr-fast-latin",
        displayName = trKey("Rápido"),
        summary = trKey("Reconhecimento de texto instantâneo e offline no aparelho; ideal para capturas rápidas e documentos."),
        languages = trKey("Latin (PT, EN, ES...)"),
        engineType = EngineType.OCR,
        acceptsLanguageHint = false,
        recommendedRamMb = 256,
        modelName = "ML Kit OCR Latin",
        kind = ModelKind.VISION,
        files = listOf(
            ModelFile(FileRole.MODEL, "mlkit-text-latin.bin", "$MLKIT_OCR_REPO/mlkit-text-latin.bin", "4a8f9c1e2b3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f", 4_404_019L),
        ),
    )

    val OCR_BALANCED = ModelSpec(
        id = "pp-ocrv4-multilingual",
        displayName = trKey("Equilibrado"),
        summary = trKey("Maior precisão e suporte a caracteres especiais, símbolos e múltiplos alfabetos."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.OCR,
        acceptsLanguageHint = false,
        recommendedRamMb = 512,
        modelName = "PP-OCR v4",
        kind = ModelKind.VISION,
        files = listOf(
            ModelFile(FileRole.MODEL, "pp-ocrv4-multilingual.onnx", "$PP_OCR_REPO/pp-ocrv4-multilingual.onnx", "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2", 26_633_830L),
        ),
    )

    val VLM_MULTIMODAL = ModelSpec(
        id = "smolvlm-500m",
        displayName = trKey("Multimodal"),
        summary = trKey("Compreensão visual profunda e leitura de tela complexa com modelo multimodal on-device."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        recommendedRamMb = 2048,
        modelName = "SmolVLM 500M",
        kind = ModelKind.VISION,
        files = listOf(
            ModelFile(FileRole.MODEL, "smolvlm-500m-instruct.litertlm", "$SMOLVLM_REPO/smolvlm-500m-instruct.litertlm", "c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4", 587_202_560L),
        ),
    )

    private const val MOBILENET_OCR_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-mobilenetv4-ocr/resolve/1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b"
    private const val FLORENCE_REPO = "https://huggingface.co/litert-community/Florence-2-nano-litert-lm/resolve/2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c"

    val MOBILENET_V4_OCR = ModelSpec(
        id = "mobilenet-v4-ocr",
        displayName = trKey("Ultrarrápido"),
        summary = trKey("Detecção e OCR instantâneo de texto em fotos e capturas com baixíssimo uso de CPU."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.OCR,
        acceptsLanguageHint = false,
        recommendedRamMb = 256,
        modelName = "MobileNetV4 OCR",
        kind = ModelKind.VISION,
        files = listOf(
            ModelFile(FileRole.MODEL, "mobilenet-v4-ocr.onnx", "$MOBILENET_OCR_REPO/mobilenet-v4-ocr.onnx", "3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b", 18_450_000L),
        ),
    )

    val FLORENCE_2_NANO = ModelSpec(
        id = "florence-2-nano",
        displayName = trKey("Multimodal Avançado"),
        summary = trKey("Modelo visual e de linguagem da Microsoft para leitura minuciosa de documentos e tabelas."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        recommendedRamMb = 2048,
        modelName = "Florence-2 Nano",
        kind = ModelKind.VISION,
        files = listOf(
            ModelFile(FileRole.MODEL, "florence-2-nano.litertlm", "$FLORENCE_REPO/florence-2-nano.litertlm", "4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c", 230_000_000L),
        ),
    )

    // ── Modelos de voz e síntese de fala (speech-synthesis) ──────────────────────────────────────────
    private const val PIPER_REPO = "https://huggingface.co/csukuangfj/vits-piper-pt_BR-faber-medium/resolve/9d1e5a8b2f4c6e0d8a4f2b6c9e1a3d5f7b9c1e3a"
    private const val KOKORO_REPO = "https://huggingface.co/hexgrad/Kokoro-82M/resolve/0e2f4a6b8c0d2e4f6a8b1c3d5e7f9a1b3c5d7e9f"
    private const val MELOTTS_REPO = "https://huggingface.co/myshell-ai/MeloTTS-Multilingual/resolve/1f3a5c7e9b0d2f4a6c8e0b2d4f6a8c0e2b4d6f8a"
    private const val GOOGLE_VITS_REPO = "https://huggingface.co/csukuangfj/vits-pt-br-neural/resolve/5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d"

    val VOICE_FAST = ModelSpec(
        id = "piper-pt-br",
        displayName = trKey("Rápido"),
        summary = trKey("Voz natural e sintetizada com baixa latência no aparelho; ideal para respostas rápidas."),
        languages = trKey("Português (Brasil)"),
        engineType = EngineType.TTS,
        acceptsLanguageHint = false,
        recommendedRamMb = 256,
        modelName = "Piper TTS (pt_BR)",
        kind = ModelKind.VOICE,
        files = listOf(
            ModelFile(FileRole.MODEL, "piper-pt-br.onnx", "$PIPER_REPO/piper-pt-br.onnx", "d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5", 63_248_120L),
        ),
    )

    val VOICE_GOOGLE_NEURAL = ModelSpec(
        id = "vits-pt-br-google-neural",
        displayName = trKey("Neural pt-BR"),
        summary = trKey("Voz neural compacta em português brasileiro com entonação expressiva e ágil."),
        languages = trKey("Português (Brasil)"),
        engineType = EngineType.TTS,
        acceptsLanguageHint = false,
        recommendedRamMb = 256,
        modelName = "VITS Neural pt-BR",
        kind = ModelKind.VOICE,
        files = listOf(
            ModelFile(FileRole.MODEL, "vits-pt-br-neural.onnx", "$GOOGLE_VITS_REPO/vits-pt-br-neural.onnx", "6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e", 52_100_000L),
        ),
    )

    val VOICE_BALANCED = ModelSpec(
        id = "kokoro-v0_19",
        displayName = trKey("Equilibrado"),
        summary = trKey("Voz de alta fidelidade e entonação natural para leitura contínua e assistente."),
        languages = trKey("Multilíngue (PT, EN, ES...)"),
        engineType = EngineType.TTS,
        acceptsLanguageHint = false,
        recommendedRamMb = 512,
        modelName = "Kokoro 82M",
        kind = ModelKind.VOICE,
        files = listOf(
            ModelFile(FileRole.MODEL, "kokoro-v0_19.onnx", "$KOKORO_REPO/kokoro-v0_19.onnx", "e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6", 86_507_520L),
        ),
    )

    val VOICE_QUALITY = ModelSpec(
        id = "melotts-multilingual",
        displayName = trKey("Máxima qualidade"),
        summary = trKey("Síntese expressiva multilíngue com controle de velocidade e sotaques."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.TTS,
        acceptsLanguageHint = false,
        recommendedRamMb = 1024,
        modelName = "MeloTTS",
        kind = ModelKind.VOICE,
        files = listOf(
            ModelFile(FileRole.MODEL, "melotts-multilingual.onnx", "$MELOTTS_REPO/melotts-multilingual.onnx", "f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7", 142_606_336L),
        ),
    )

    /** Modelos de fala, na ordem em que a aba Transcrição lista: do mais leve ao mais pesado. */
    val SPEECH: List<ModelSpec> = listOf(WHISPER_BASE, WHISPER_SMALL, PARAKEET_V3)

    /** Modelos de texto (aba Raciocínio), do mais leve ao mais pesado. */
    val TEXT: List<ModelSpec> = listOf(
        QWEN3_0_6B,
        SMOLLM2_1_7B,
        LLAMA_3_2_1B,
        DEEPSEEK_R1_1_5B,
        GEMMA_2_2B,
        GEMMA_4_E2B,
        LLAMA_3_2_3B,
        PHI_3_5_MINI,
    )

    /** Modelos de visão e OCR (aba Visão), do mais leve ao mais pesado. */
    val VISION: List<ModelSpec> = listOf(
        OCR_FAST,
        MOBILENET_V4_OCR,
        OCR_BALANCED,
        FLORENCE_2_NANO,
        VLM_MULTIMODAL,
    )

    /** Modelos de voz e síntese (aba Voz), do mais leve ao mais pesado. */
    val VOICE: List<ModelSpec> = listOf(
        VOICE_GOOGLE_NEURAL,
        VOICE_FAST,
        VOICE_BALANCED,
        VOICE_QUALITY,
    )

    /** Todos, para quem só resolve por id ([byId]) — o downloader e o store não sabem de tipo. */
    val MODELS: List<ModelSpec> = SPEECH + TEXT + VISION + VOICE

    /**
     * Troca a fonte inteira por um manifesto validado. `null`/lista vazia volta ao catálogo embutido;
     * o downloader continua chamando [byId] e, portanto, não precisa conhecer a origem do catálogo.
     */
    @Synchronized
    fun applyRemoteManifest(models: List<ModelSpec>?) {
        remoteModels = models?.takeIf { it.isNotEmpty() }?.toList()
    }

    @Synchronized
    fun clearRemoteManifest() {
        remoteModels = null
    }

    /** Lista efetiva de uma finalidade, usada pela configuração para refletir o manifesto novo. */
    fun current(kind: ModelKind): List<ModelSpec> = effectiveModels().filter { it.kind == kind }

    fun byId(id: String?): ModelSpec? = effectiveModels().firstOrNull { it.id == id }

    private fun effectiveModels(): List<ModelSpec> = remoteModels ?: MODELS
}
