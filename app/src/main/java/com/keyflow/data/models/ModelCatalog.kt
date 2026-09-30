package com.keyflow.data.models

import com.keyflow.i18n.trKey

/** Para que serve o modelo: fala (sherpa-onnx, aba Transcrição) ou texto (LLM local, aba Raciocínio). */
enum class ModelKind { SPEECH, TEXT }

/**
 * Como o engine roda o modelo: [WHISPER]/[TRANSDUCER] montam o `OfflineRecognizer` do sherpa-onnx (NM-T8);
 * [LITERT_LM] é um LLM do Google rodado pelo LiteRT-LM no processo `:reasoning` (reasoning-models).
 */
enum class EngineType { WHISPER, TRANSDUCER, LITERT_LM }

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
        displayName = trKey("Equilibrado"),
        summary = trKey("Mais capaz que o Leve; pesado — melhor em aparelhos com bastante memória."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        // Estimativa (memória de CPU ~1,7 GB no README, num aparelho topo de linha). Revisar na RSN-T19.
        recommendedRamMb = 4096,
        modelName = "Gemma 4 E2B",
        kind = ModelKind.TEXT,
        files = listOf(
            ModelFile(FileRole.MODEL, "gemma-4-E2B-it.litertlm", "$GEMMA_4_REPO/gemma-4-E2B-it.litertlm", "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", 2_588_147_712L),
        ),
    )

    /** Modelos de fala, na ordem em que a aba Transcrição lista: do mais leve ao mais pesado. */
    val SPEECH: List<ModelSpec> = listOf(WHISPER_BASE, WHISPER_SMALL, PARAKEET_V3)

    /** Modelos de texto (aba Raciocínio), do mais leve ao mais pesado. */
    val TEXT: List<ModelSpec> = listOf(QWEN3_0_6B, GEMMA_4_E2B)

    /** Todos, para quem só resolve por id ([byId]) — o downloader e o store não sabem de tipo. */
    val MODELS: List<ModelSpec> = SPEECH + TEXT

    fun byId(id: String?): ModelSpec? = MODELS.firstOrNull { it.id == id }
}
