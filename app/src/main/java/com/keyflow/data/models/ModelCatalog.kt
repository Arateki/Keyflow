package com.keyflow.data.models

import com.keyflow.BuildConfig
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
    /**
     * O prompt do modelo (metadado do `.litertlm`) já abre o `<think>`: a saída começa dentro do pensamento e só traz o
     * `</think>`. O `LiteRtBackend` descarta tudo até ele (`ThoughtStripper`).
     */
    val promptOpensThink: Boolean = false,
    /**
     * Falso = ainda não provado carregando no LiteRT-LM de um aparelho real: o modelo só aparece nos builds de debug
     * ([ModelCatalog.visibleIn]). Vira verdadeiro depois do roteiro manual no A32.
     */
    val validatedOnDevice: Boolean = true,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }

    fun file(role: FileRole): ModelFile? = files.firstOrNull { it.role == role }
}

/**
 * Catálogo curado embutido (CAT-02, G3/G4/G5). Pesos baixados arquivo a arquivo dos repositórios que
 * o próprio sherpa-onnx publica no HuggingFace (csukuangfj) e dos modelos de texto do `litert-community`.
 *
 * Tamanhos e SHA256 conferidos na API do HF em 2026-09-24 (`/api/models/<repo>/tree/<commit>`: o `oid`
 * do LFS é o SHA256 do conteúdo — confirmado baixando o decoder do Parakeet) e reconferidos em 2026-10-24 por
 * `scripts/verify-model-catalog.py`. Os `tokens.txt` não estão no LFS: SHA calculado baixando o arquivo. Todas as
 * URLs responderam 206 a um pedido de faixa, que é o que a retomada da fila de downloads usa (`Range`).
 *
 * As URLs apontam para o **commit** do repositório, não para `main`: se o upstream mexer num arquivo,
 * o app publicado continua baixando exatamente a versão cujo SHA conhece (mitiga o risco aceito na G5).
 *
 * **Só entra entrada conferida.** Em 2026-10-24 achou-se que 15 entradas (6 de texto, 5 de visão, 4 de voz) tinham
 * repositório, commit e SHA-256 inventados — o download falhava em produção. Para isso não voltar: o `PinSanityTest`
 * recusa pin com cara de sequência (sem rede) e o `scripts/verify-model-catalog.py` confere cada URL, tamanho e
 * SHA-256 contra a API do HF. Entrada nova: rode o script antes de commitar e só use modelo com licença sem
 * GPL/AGPL/LGPL e sem cláusula "não comercial", baixável sem login.
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
    // SmolLM2 1.7B e DeepSeek R1 1.5B entraram em 2026-10-25 (decisão do Yan), cada um com commit, tamanho e SHA-256 lidos
    // da API do HF e o SHA-256 conferido baixando o arquivo inteiro; ver o STATE.md ("Catálogo com mais dois modelos de texto").
    // Saíram em 2026-10-24 (repositório `-litert-lm` inexistente, commit e SHA inventados): Phi-3.5 Mini, Llama 3.2 1B/3B e
    // Gemma 2 2B (e as versões antigas, inventadas, do SmolLM2 e do DeepSeek). Llama (Llama 3.2 Community License) e Gemma 2
    // (Gemma Terms) têm restrições de uso e os repositórios reais são gated (login): voltam só por decisão do dono do produto.
    private const val GEMMA_4_REPO = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
    private const val GEMMA_4_E4B_REPO = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/e6a3892a8c22cf87ff99583996149cf3cb32452b"
    private const val QWEN3_REPO = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76"
    private const val SMOLLM2_REPO = "https://huggingface.co/litert-community/SmolLM2-1.7B-Instruct/resolve/c0ee765a9f197467ead8f22b9bf7bbeb4e6307c7"
    private const val DEEPSEEK_R1_REPO = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/9fd875541bb0f277e4b3cefe7d9622c4c1de2fb5"

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
     * SmolLM2 1.7B Instruct (Hugging Face), INT8 dinâmico só nos pesos (`wi8`) com ativações em FP32. Licença Apache 2.0.
     * Treinado quase só em inglês: em português responde pior que o Qwen3 — por isso o idioma vem declarado no cartão ("Inglês").
     */
    val SMOLLM2_1_7B = ModelSpec(
        id = "smollm2-1.7b-litertlm",
        displayName = trKey("Intermediário"),
        summary = trKey("Modelo de uso geral com 1,7 bilhão de parâmetros, treinado sobretudo em inglês; mais pesado que o Leve."),
        languages = trKey("Inglês"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        // ESTIMATIVA, não medida: ~1,6 GiB de arquivo (pesos INT8) mais ativações em FP32; no emulador de 2,5 GB o
        // `:reasoning` passou de 1,4 GB de RAM e foi morto. 3072 = arquivo + memória de trabalho com folga. A medir no A32 (RSN-T19).
        recommendedRamMb = 3072,
        modelName = "SmolLM2 1.7B",
        kind = ModelKind.TEXT,
        validatedOnDevice = false,
        files = listOf(
            ModelFile(
                FileRole.MODEL, "SmolLM2-1_7B-Instruct_dynamic_wi8_afp32.litertlm", "$SMOLLM2_REPO/SmolLM2-1_7B-Instruct_dynamic_wi8_afp32.litertlm",
                "3c3e693157e2fb92b862b572cff74b7a353f81eb232cef682bf0fcc91855f8f1", 1_730_949_280L,
            ),
        ),
    )

    /**
     * DeepSeek-R1-Distill-Qwen 1.5B (DeepSeek), INT8 (`q8`), contexto de 4096 (`ekv4096`). Licença MIT; derivado do
     * Qwen2.5-Math-1.5B (Apache 2.0). Modelo que "pensa" antes de responder: o `<think>…</think>` sai do texto (canal
     * "thought" do [LiteRtBackend]), mas o tempo de geração conta. O card do HF mede ~2,2 GB de pico de memória na CPU.
     */
    val DEEPSEEK_R1_1_5B = ModelSpec(
        id = "deepseek-r1-1.5b-litertlm",
        displayName = trKey("Raciocínio"),
        summary = trKey("Escreve um raciocínio passo a passo antes de responder, por isso demora mais; treinado em inglês e chinês."),
        languages = trKey("Inglês"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        // ESTIMATIVA, não medida: o card do HF mede 2221 MB de pico de RSS na CPU de um S24 Ultra (contexto 4096); 4096 = esse pico
        // mais o que o sistema e os outros apps ocupam num aparelho de 4 GB, e a guarda de RAM do app usa a metade (2048 ≈ o pico).
        // A medir no A32 (RSN-T19).
        recommendedRamMb = 4096,
        modelName = "DeepSeek R1 1.5B",
        kind = ModelKind.TEXT,
        promptOpensThink = true,
        validatedOnDevice = false,
        files = listOf(
            ModelFile(
                FileRole.MODEL, "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                "$DEEPSEEK_R1_REPO/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                "69b35f01759eed765641ab4af589bbe98131fd2825662a086d9037409b8c1295", 1_833_451_520L,
            ),
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
        recommendedRamMb = 4096,
        modelName = "Gemma 4 E2B",
        kind = ModelKind.TEXT,
        files = listOf(
            ModelFile(FileRole.MODEL, "gemma-4-E2B-it.litertlm", "$GEMMA_4_REPO/gemma-4-E2B-it.litertlm", "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", 2_588_147_712L),
        ),
    )

    /**
     * Gemma 4 E4B (Google), arquivo genérico (CPU/GPU) — não as variantes `-gpu`/`-web` nem as de NPU. Licença Apache 2.0 (a mesma do E2B;
     * o Gemma 4 não usa os Termos do Gemma das versões 1 a 3). Entrou em 2026-10-26, por decisão do Yan, mesmo sem caber no A32: o README do
     * repositório mede 3283 MB de pico de CPU (`ru_maxrss`, S26 Ultra, contexto de 2048) contra ~1,4–1,7 GB do E2B, e o arquivo tem 3,66 GB.
     */
    val GEMMA_4_E4B = ModelSpec(
        id = "gemma-4-e4b-litertlm",
        displayName = trKey("Avançado"),
        summary = trKey("Mais capaz que o Equilibrado; bem pesado — só para aparelhos com muita memória."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        // ESTIMATIVA, não medida em aparelho nosso: o pico de CPU do README é 3283 MB (S26 Ultra) contra 1733 MB do E2B (que usa 4096), então
        // a mesma proporção (~2,4x) dá ~7,7 GB; 8192 é o degrau seguinte. A guarda de RAM do app exige a metade (4096) livre ao carregar — acima
        // do pico de 3283 MB, como o E2B (2048 para 1733) — e o aviso de "pesado" aparece em todo aparelho com menos de 8192 MB (o A32 e os de 6 GB).
        // Não cabe no A32 (4 GB); a medir num aparelho de 8 GB ou mais.
        recommendedRamMb = 8192,
        modelName = "Gemma 4 E4B",
        kind = ModelKind.TEXT,
        validatedOnDevice = false,
        files = listOf(
            ModelFile(FileRole.MODEL, "gemma-4-E4B-it.litertlm", "$GEMMA_4_E4B_REPO/gemma-4-E4B-it.litertlm", "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", 3_659_530_240L),
        ),
    )

    /** Modelos de fala, na ordem em que a aba Transcrição lista: do mais leve ao mais pesado. */
    val SPEECH: List<ModelSpec> = listOf(WHISPER_BASE, WHISPER_SMALL, PARAKEET_V3)

    /** Todos os modelos de texto conhecidos, do mais leve ao mais pesado, validados ou não. */
    val TEXT_ALL: List<ModelSpec> = listOf(
        QWEN3_0_6B,
        SMOLLM2_1_7B,
        DEEPSEEK_R1_1_5B,
        GEMMA_4_E2B,
        GEMMA_4_E4B,
    )

    /**
     * O que o build mostra: em release só os [ModelSpec.validatedOnDevice]; em debug, todos. SmolLM2, DeepSeek e Gemma 4 E4B saem do
     * gate depois do roteiro manual no A32 (`.specs/features/reasoning-models/tasks.md`).
     */
    fun visibleIn(models: List<ModelSpec>, debug: Boolean): List<ModelSpec> = models.filter { debug || it.validatedOnDevice }

    /** Modelos de texto (aba Raciocínio), do mais leve ao mais pesado, como este build os mostra. */
    val TEXT: List<ModelSpec> = visibleIn(TEXT_ALL, BuildConfig.DEBUG)

    /**
     * Modelos de visão e OCR (aba Visão). Vazia de propósito: as cinco entradas antigas tinham repositório, commit e
     * SHA-256 inventados (nenhum download funcionava) e saíram em 2026-10-24. A Visão está sendo refeita na branch
     * `equipe/visao` (VC-T2a), que traz entradas novas — só com pin conferido por `scripts/verify-model-catalog.py`.
     */
    val VISION: List<ModelSpec> = emptyList()

    /**
     * Modelos de voz e síntese (aba Voz). Vazia de propósito: as quatro entradas antigas tinham pins inventados e
     * saíram em 2026-10-24; as vozes do Piper (e o Kokoro) dependem do eSpeak (GPL) e o app ainda não sintetiza fala.
     * A entrada nova vem com a feature de síntese de fala, já conferida.
     */
    val VOICE: List<ModelSpec> = emptyList()

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
