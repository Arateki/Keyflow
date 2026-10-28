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
 * Empresa que faz o modelo (não quem o hospeda nem quem o converteu): dá o logo do card. É dado do catálogo, não
 * deduzido do nome na tela. [displayName] é nome próprio, igual em todos os idiomas.
 */
enum class ModelVendor(val displayName: String) {
    GOOGLE("Google"),
    OPENAI("OpenAI"),
    QWEN("Alibaba Qwen"),
    DEEPSEEK("DeepSeek"),
    HUGGING_FACE("Hugging Face"),
    NVIDIA("NVIDIA"),
    PADDLEPADDLE("PaddlePaddle"),
}

/**
 * Se o modelo de texto "pensa" antes de responder (só informativo, vira uma linha no card). [NONE] é o modelo de instrução comum e o
 * valor de quem não declara; [OPTIONAL] pensa quando o pedido liga o modo (`enable_thinking`, lido pelo template do `.litertlm`);
 * [ALWAYS] sempre abre um raciocínio, que o app esconde da resposta mas que conta no tempo de geração. Valor que esta versão do app
 * não conhece, num manifesto remoto, vale [NONE].
 */
enum class ThinkingSupport { NONE, OPTIONAL, ALWAYS }

/**
 * O que o modelo faz de verificado, além do que o tipo e o idioma já dizem; vira etiqueta no card e **não inventa nada**: só entra o que o
 * fabricante declara (ou o que foi conferido) e a fonte fica no comentário de cada entrada do catálogo. [READ_TEXT] lê o texto de uma captura de
 * tela (OCR); [DESCRIBE_IMAGE] e [ANSWER_ABOUT_SCREEN] são as capacidades de visão dos Gemma 4 — ainda NÃO declaradas, porque falta provar no
 * aparelho que o `.litertlm` carrega com visão (VC-T15 da Visão); [TOOLS] ("Ferramentas"): o fabricante declara chamada de funções/uso de
 * ferramentas no modelo (não quer dizer que o app já o use como agente: é o que o modelo sabe fazer); [NATIVE_PORTUGUESE]: o fabricante
 * lista o português entre os idiomas de treino (Parakeet TDT 0.6B v3). Valor que esta versão do app não conhece, num manifesto remoto, é
 * ignorado.
 */
enum class ModelCapability { READ_TEXT, DESCRIBE_IMAGE, ANSWER_ABOUT_SCREEN, TOOLS, NATIVE_PORTUGUESE }

/**
 * Papel de cada arquivo no modelo — o engine monta a config por papel, não pelo nome do arquivo.
 * [MODEL] é o arquivo único `.litertlm` dos modelos de texto. [DETECTOR], [RECOGNIZER] e [DICTIONARY] são os três
 * arquivos do OCR (vision-capture): detector de linhas de texto, reconhecedor CTC e a lista de caracteres dele.
 */
enum class FileRole { ENCODER, DECODER, JOINER, TOKENS, MODEL, DETECTOR, RECOGNIZER, DICTIONARY }

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
     * ([ModelCatalog.visibleIn]). Vira verdadeiro depois de responder num aparelho real.
     */
    val validatedOnDevice: Boolean = true,
    /** Quem faz o modelo; `null` só em manifesto remoto sem o campo. Todo modelo do catálogo embutido declara o seu (`ModelCatalogTest`). */
    val vendor: ModelVendor? = null,
    /** Etiqueta de modo pensamento no card (só os modelos de texto declaram; o resto fica em [ThinkingSupport.NONE]). */
    val thinking: ThinkingSupport = ThinkingSupport.NONE,
    /** O que o modelo faz de verificado, em etiquetas do card; vazio = nenhuma além do idioma, do pensamento e da RAM (ver [ModelCapability]). */
    val capabilities: Set<ModelCapability> = emptySet(),
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

    /**
     * Versão do catálogo embutido, no formato `AAAAMMDDNN`. **Suba-a sempre que um pin (commit, tamanho ou SHA-256) mudar ou um modelo entrar.**
     * O manifesto remoto (`catalogVersion`) só vale se for maior ou igual a ela: sem isso, um manifesto ou cache antigo substituiria o catálogo
     * inteiro e devolveria, por exemplo, o SHA legado do Qwen3 (o `ModelCatalogTest` confere a impressão digital dos pins contra esta versão).
     */
    const val EMBEDDED_VERSION = 2026102701

    /** Impressão digital dos pins embutidos (id, nome, tamanho e SHA-256 de cada arquivo): muda se um pin mudar sem [EMBEDDED_VERSION] subir. */
    fun pinsFingerprint(models: List<ModelSpec> = MODELS): String {
        val lines = models.sortedBy { it.id }.flatMap { m -> m.files.map { "${m.id}:${it.fileName}:${it.bytes}:${it.sha256}" } }
        return java.security.MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private const val WHISPER_BASE_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/bb53ee204431c90d314c1cc08d28d23e5b7927cc"
    private const val WHISPER_SMALL_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309"
    private const val PARAKEET_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78"

    private const val OCR_PADDLE_REPO = "https://huggingface.co/monkt/paddleocr-onnx/resolve/7b02d0a30a07ba2b92ad1ff5a8941ae2c633de65"

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
        vendor = ModelVendor.OPENAI,
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
        vendor = ModelVendor.OPENAI,
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
        vendor = ModelVendor.NVIDIA,
        // O card dizia "com português nativo" no resumo: o português está entre os 25 idiomas de treino do modelo.
        capabilities = setOf(ModelCapability.NATIVE_PORTUGUESE),
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
    private const val QWEN3_REPO = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/fed81d17517e6ea64362e917ecf7e5accff12492"
    private const val QWEN2_5_REPO = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/2f23bacf8ec78b83dfcd7271c5cb25777b1be5e0"
    private const val SMOLLM2_REPO = "https://huggingface.co/litert-community/SmolLM2-1.7B-Instruct/resolve/c0ee765a9f197467ead8f22b9bf7bbeb4e6307c7"
    private const val DEEPSEEK_R1_REPO = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/9fd875541bb0f277e4b3cefe7d9622c4c1de2fb5"

    /**
     * Qwen3 0.6B (Alibaba), INT8 dinâmico, contexto de 4096. Licença Apache 2.0.
     *
     * Pin trocado em 2026-10-27 (decisão do Yan; mesmo id) do commit `a3c5d805…` (SHA `555579ff…`, 614236160 B) para `fed81d17…`:
     * o arquivo antigo trazia só marcadores ChatML, sem o Jinja que consulta `enable_thinking`, e pensava sempre (206 s para uma
     * resposta curta no Vaio TL10, com o pensamento escondido); o novo respeita `enable_thinking=false` (9,8 s e 23,2 s no mesmo
     * aparelho). Pico de VmRSS igual (~1,9 GB). Quem tem o arquivo antigo instalado vê o card como "Atualizar modelo" (`ModelStore.outdatedIds`).
     * Ver `.specs/features/reasoning-models/qwen-sem-pensar.md`.
     */
    val QWEN3_0_6B = ModelSpec(
        id = "qwen3-0.6b-litertlm",
        displayName = trKey("Leve"),
        summary = trKey("Pequeno e ágil; bom para ajustes rápidos de texto em qualquer aparelho."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        // Medido (2026-10-27, pico de VmRSS do `:reasoning`, pedido curto): 1,66 GB no A32 (3,7 GB) e 1,7–1,9 GB no Tab S10 Lite (5,8 GB) — bem acima
        // do arquivo (585 MB). 3072 = RAM total em que ele roda sem aviso de "pesado" (o A32 não o mostra); a guarda de carga exige 80% disso (2458 MB).
        recommendedRamMb = 3072,
        modelName = "Qwen3 0.6B",
        kind = ModelKind.TEXT,
        vendor = ModelVendor.QWEN,
        // Card do Qwen/Qwen3-0.6B: "seamless switching between thinking mode and non-thinking mode"; o template da revisão fed81d17… lê `enable_thinking`.
        thinking = ThinkingSupport.OPTIONAL,
        // Ferramentas: o card `Qwen/Qwen3-0.6B` ("Agentic Use": "Qwen3 excels in tool calling capabilities"). Conferido em 2026-10-27.
        capabilities = setOf(ModelCapability.TOOLS),
        files = listOf(
            ModelFile(FileRole.MODEL, "Qwen3-0.6B.litertlm", "$QWEN3_REPO/Qwen3-0.6B.litertlm", "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108", 613_722_264L),
        ),
    )

    /**
     * Qwen2.5 1.5B Instruct (Alibaba), INT8 (`q8`), contexto de 4096 (`ekv4096`). Licença Apache 2.0 (cards do original e da conversão, `gated: false`).
     * Modelo de instrução comum, sem modo de pensamento: responde direto. Entrou em 2026-10-27, por decisão do Yan, como a opção multilíngue
     * entre o Leve e o Intermediário; commit, tamanho e SHA-256 lidos da API do HF e o SHA-256 conferido baixando o arquivo inteiro
     * (`.specs/features/reasoning-models/qwen-sem-pensar.md`).
     */
    val QWEN2_5_1_5B = ModelSpec(
        id = "qwen2.5-1.5b-litertlm",
        displayName = trKey("Versátil"),
        summary = trKey("Uso geral e multilíngue; responde direto e bem em português."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        // Medido (2026-10-27, Vaio TL10, 7,6 GB): resposta quase perfeita em português, ~10 s (curta) e ~20 s (longa), pico de VmRSS do
        // `:reasoning` de 2,3 GB; o README do conversor mede 2,22 GB (q8, contexto 4096). 4096: o aviso de "pesado" aparece abaixo disso e a guarda
        // de carga exige 80% (3277 MB), como no DeepSeek (mesma base, mesma quantização e mesmo contexto). Não tentado no A32.
        recommendedRamMb = 4096,
        modelName = "Qwen2.5 1.5B",
        kind = ModelKind.TEXT,
        vendor = ModelVendor.QWEN,
        thinking = ThinkingSupport.NONE,
        // Ferramentas: o chat template oficial do `Qwen/Qwen2.5-1.5B-Instruct` (tokenizer_config.json) tem o bloco `tools` e as marcas `<tool_call>`. Conferido em 2026-10-27.
        capabilities = setOf(ModelCapability.TOOLS),
        files = listOf(
            ModelFile(
                FileRole.MODEL, "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                "$QWEN2_5_REPO/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                "c4a9b7289365ab80fe599b844b77ad253eb3c53fb0e5d963d699146a6f01255e", 1_597_922_856L,
            ),
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
        // Medido (2026-10-27, pico de VmRSS do `:reasoning`): 3,3 GB no Tab S10 Lite (5,8 GB, 2,3 GB livres) — o dobro do arquivo, por causa das
        // ativações em FP32 —, e no A32 (3,7 GB) o lmkd o matou ao passar ~1,7 GB. 6144: a guarda de carga exige 80% (4916 MB), o que barra o
        // A32 (~3,6–3,8 GB) e libera o tablet de 6 GB (~5,7 GB, que ainda vê o aviso de "pesado"). É por isso o mais pesado em RAM antes do
        // Gemma 4 E4B, embora a lista siga a ordem de download (tamanho do arquivo).
        recommendedRamMb = 6144,
        modelName = "SmolLM2 1.7B",
        kind = ModelKind.TEXT,
        vendor = ModelVendor.HUGGING_FACE,
        thinking = ThinkingSupport.NONE,
        // Ferramentas: o card `HuggingFaceTB/SmolLM2-1.7B-Instruct` ("supports ... function calling"; seção "Function calling": 27% no BFCL). Conferido em 2026-10-27.
        capabilities = setOf(ModelCapability.TOOLS),
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
        // Medido (2026-10-27, pico de VmRSS do `:reasoning`): 1,73 GB no A32 (3,7 GB, 1,1–1,5 GB livres) e 1,9–2,5 GB no Tab S10 Lite (5,8 GB);
        // o card do HF mede 2221 MB num S24 Ultra. 4096: no A32 mostra o aviso de "pesado", mas a guarda de carga exige só 80% (3277 MB) e o
        // deixa carregar, como funcionou.
        recommendedRamMb = 4096,
        modelName = "DeepSeek R1 1.5B",
        kind = ModelKind.TEXT,
        promptOpensThink = true,
        vendor = ModelVendor.DEEPSEEK,
        // O template abre o `<think>` em toda resposta (por isso o `promptOpensThink`); não há como desligar.
        thinking = ThinkingSupport.ALWAYS,
        // Sem etiqueta de Ferramentas: o card `deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B` não declara chamada de funções (conferido em 2026-10-27).
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
        // Medido (2026-10-27, pico de VmRSS do `:reasoning`): 1,72 GB no A32 (3,7 GB), 2,1–2,2 GB no Tab S10 Lite (5,8 GB) e 2,2 GB no Vaio TL10
        // (7,6 GB). 4096: aviso de "pesado" no A32, onde a guarda de carga (80% = 3277 MB) o deixa carregar, como funcionou.
        recommendedRamMb = 4096,
        modelName = "Gemma 4 E2B",
        kind = ModelKind.TEXT,
        vendor = ModelVendor.GOOGLE,
        // Pensamento configurável: card do google/gemma-4-E2B-it (huggingface.co/google/gemma-4-E2B-it), "Core Capabilities > Thinking" e "Thinking
        // Mode Configuration" (liga com `<|think|>` no começo do prompt de sistema; desligado, o E2B/E4B nem emite o canal). O template do próprio
        // `.litertlm` injeta esse token quando `enable_thinking` é verdadeiro (fixture `gemma-e2b.jinja`, linhas 157-162).
        thinking = ThinkingSupport.OPTIONAL,
        // Ferramentas: a doc oficial "Function calling with Gemma 4" (ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4) e o card
        // `google/gemma-4-E2B-it` ("native function-calling support"). Visão NÃO declarada (VC-T15). Conferido em 2026-10-27.
        capabilities = setOf(ModelCapability.TOOLS),
        files = listOf(
            ModelFile(FileRole.MODEL, "gemma-4-E2B-it.litertlm", "$GEMMA_4_REPO/gemma-4-E2B-it.litertlm", "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", 2_588_147_712L),
        ),
    )

    /**
     * Gemma 4 E4B (Google), arquivo genérico (CPU/GPU) — não as variantes `-gpu`/`-web` nem as de NPU. Licença Apache 2.0 (a mesma do E2B;
     * o Gemma 4 não usa os Termos do Gemma das versões 1 a 3). Entrou em 2026-10-26, por decisão do Yan, mesmo sem caber no A32 (o arquivo tem 3,66 GB);
     * liberado no release em 2026-10-27, depois de responder num Vaio TL10 de 8 GB.
     */
    val GEMMA_4_E4B = ModelSpec(
        id = "gemma-4-e4b-litertlm",
        displayName = trKey("Avançado"),
        summary = trKey("Mais capaz que o Equilibrado; bem pesado — só para aparelhos com muita memória."),
        languages = trKey("Multilíngue"),
        engineType = EngineType.LITERT_LM,
        acceptsLanguageHint = false,
        // Medido (2026-10-27, pico de VmRSS do `:reasoning`): 4,4–4,5 GB no Vaio TL10 (7,6 GB, 4,8 GB livres); o README mede 3283 MB (CPU, S26 Ultra,
        // contexto 2048). Não tentado no A32. 8192: a guarda de carga exige 80% (6554 MB), o que barra o A32 e o tablet de 6 GB (~5,7 GB) e libera o
        // de 8 GB (~7,4 GB), que ainda vê o aviso de "pesado".
        recommendedRamMb = 8192,
        modelName = "Gemma 4 E4B",
        kind = ModelKind.TEXT,
        vendor = ModelVendor.GOOGLE,
        // Pensamento configurável: card do google/gemma-4-E4B-it (huggingface.co/google/gemma-4-E4B-it), "Core Capabilities > Thinking" e "Thinking
        // Mode Configuration" (liga com `<|think|>` no começo do prompt de sistema; desligado, o E2B/E4B nem emite o canal). O template do próprio
        // `.litertlm` injeta esse token quando `enable_thinking` é verdadeiro (fixture `gemma-e2b.jinja`, linhas 157-162).
        thinking = ThinkingSupport.OPTIONAL,
        // Ferramentas: a mesma doc oficial do Gemma 4 e o card `google/gemma-4-E4B-it`. Visão NÃO declarada (VC-T15). Conferido em 2026-10-27.
        capabilities = setOf(ModelCapability.TOOLS),
        files = listOf(
            ModelFile(FileRole.MODEL, "gemma-4-E4B-it.litertlm", "$GEMMA_4_E4B_REPO/gemma-4-E4B-it.litertlm", "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", 3_659_530_240L),
        ),
    )

    /** Modelos de fala, na ordem em que a aba Transcrição lista: do mais leve ao mais pesado. */
    val SPEECH: List<ModelSpec> = listOf(WHISPER_BASE, WHISPER_SMALL, PARAKEET_V3)

    /** Todos os modelos de texto conhecidos, do menor ao maior arquivo (a ordem de download). A de RAM, em `recommendedRamMb`, difere: o SmolLM2 pesa mais que o DeepSeek e o Gemma 4 E2B. */
    val TEXT_ALL: List<ModelSpec> = listOf(
        QWEN3_0_6B,
        QWEN2_5_1_5B,
        SMOLLM2_1_7B,
        DEEPSEEK_R1_1_5B,
        GEMMA_4_E2B,
        GEMMA_4_E4B,
    )

    /**
     * O que o build mostra: em release só os [ModelSpec.validatedOnDevice]; em debug, todos. Desde 2026-10-27 os cinco modelos de texto
     * estão validados (responderam em aparelho real); o flag segue como gate para o próximo modelo que entrar sem prova.
     */
    fun visibleIn(models: List<ModelSpec>, debug: Boolean): List<ModelSpec> = models.filter { debug || it.validatedOnDevice }

    /** Modelos de texto (aba Raciocínio), do mais leve ao mais pesado, como este build os mostra. */
    val TEXT: List<ModelSpec> = visibleIn(TEXT_ALL, BuildConfig.DEBUG)

    /**
     * OCR da Visão (vision-capture, VC-T2a): PaddleOCR em ONNX, par do repositório `monkt/paddleocr-onnx` — detector
     * PP-OCRv3 mobile (`detection/v3/det.onnx`) + reconhecedor PP-OCRv5 mobile para o alfabeto latino
     * (`languages/latin/rec.onnx`) + o dicionário dele (`languages/latin/dict.txt`), 10.294.339 B no total.
     *
     * Licença: Apache-2.0. O cartão do repositório diz `license: apache-2.0` e a origem, `PaddlePaddle/PaddleOCR` (e os cartões
     * `PaddlePaddle/latin_PP-OCRv5_mobile_rec`), é Apache-2.0; o texto está em `app/licenses/Apache-2.0_paddleocr.txt`. O
     * repositório não traz arquivo `LICENSE`. Pins conferidos em 2026-10-27: o commit é o HEAD do repositório, os tamanhos e o
     * `lfs.oid` vêm da API do HF, os SHA-256 foram recalculados baixando os três arquivos e os três respondem 206 a `Range`
     * (`scripts/verify-model-catalog.py`). Plano B, se o par reprovar na VC-T6: `deepghs/paddleocr` (`license: other`; ver
     * `.specs/features/vision-capture/context.md` V3) — a troca é só dado deste arquivo.
     *
     * O README do repositório manda casar detector e reconhecedor da mesma geração (v3 com v3, v5 com v5). Este par mistura as duas
     * de propósito, porque o detector v5 tem 84 MB; se a VC-T6 medir perda de qualidade por causa disso, o par muda.
     *
     * `validatedOnDevice = false`: nenhum motor roda o modelo ainda (VC-T2b e VC-T6), então o release não o oferece; só o debug.
     */
    val OCR_PADDLE_LATIN = ModelSpec(
        id = "ocr-paddle-latin",
        displayName = trKey("Leitura de texto"),
        summary = trKey("Lê o texto de uma captura de tela, no aparelho."),
        languages = trKey("Alfabeto latino"),
        engineType = EngineType.OCR,
        acceptsLanguageHint = false,
        // ESTIMATIVA, não medida: o modelo em disco tem ~10 MB; a meta do design é pico ≤ 250 MB com a tela cheia em faixas. A medir
        // no A32 (VC-T6).
        recommendedRamMb = 512,
        modelName = "PaddleOCR",
        kind = ModelKind.VISION,
        validatedOnDevice = false,
        vendor = ModelVendor.PADDLEPADDLE,
        capabilities = setOf(ModelCapability.READ_TEXT),
        files = listOf(
            ModelFile(
                FileRole.DETECTOR, "det.onnx", "$OCR_PADDLE_REPO/detection/v3/det.onnx",
                "ee40e80071ba3a320d4efda75f3e22047a7d049e9bf7bcaaf9daea23fc21b935", 2_429_873L,
            ),
            ModelFile(
                FileRole.RECOGNIZER, "rec.onnx", "$OCR_PADDLE_REPO/languages/latin/rec.onnx",
                "614ffc2d6d3902d360fad7f1b0dd455ee45e877069d14c4e51a99dc4ef144409", 7_862_832L,
            ),
            ModelFile(
                FileRole.DICTIONARY, "dict.txt", "$OCR_PADDLE_REPO/languages/latin/dict.txt",
                "3c0a8a79b612653c25f765271714f71281e4e955962c153e272b7b8c1d2b13ff", 1_634L,
            ),
        ),
    )

    /** Todos os modelos de visão e OCR conhecidos, validados ou não. As cinco entradas antigas (pins inventados) saíram em 2026-10-24. */
    val VISION_ALL: List<ModelSpec> = listOf(OCR_PADDLE_LATIN)

    /** Modelos de visão e OCR (aba Visão), como este build os mostra: o OCR só no debug até ser validado (VC-T6). */
    val VISION: List<ModelSpec> = visibleIn(VISION_ALL, BuildConfig.DEBUG)

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
