package com.keyflow.agent

import org.json.JSONObject

/**
 * O quanto uma ferramenta mexe no app (TLS-01). É o que, junto do modo de confirmação, decide se ela roda direto, pede permissão ou
 * nem roda ([ToolPolicy]).
 */
enum class ToolRisk {
    /** Só lê ou mostra; não muda nada. Roda sem perguntar (A2). */
    READ,

    /** Muda algo, mas é de baixo risco e dá para desfazer (instalar uma skin, mudar um ajuste). Pede permissão; é a única que aceita "permitir sempre". */
    WRITE,

    /** Apaga ou sobrescreve o que não volta sozinho (remover uma skin). Pede permissão SEMPRE — nenhum ajuste, nem "permitir sempre", muda isso. */
    DESTRUCTIVE,
}

/** De onde veio o pedido. O servidor MCP (fase 3) é a segunda porta para o mesmo registro e a mesma política (MCP-06). */
enum class ToolOrigin { CHAT, EXTERNAL }

/** O que uma ferramenta recebe além dos argumentos. Pequeno de propósito: nada aqui dá acesso a segredo (TLS-03). */
class ToolContext(
    val origin: ToolOrigin = ToolOrigin.CHAT,
    /** O id da chamada (o `tool_use.id` do modelo), para a ferramenta correlacionar o que mostra ao usuário. */
    val callId: String = "",
)

/** Uma imagem que o app mostra ao usuário junto do pedido de confirmação (a folha de contato de uma skin, por exemplo). */
class ToolPreview(
    /** `image/png` etc. */
    val mediaType: String,
    val bytes: ByteArray,
    val caption: String? = null,
)

/**
 * Uma imagem que acompanha o RESULTADO de uma ferramenta e vai ao modelo (A4: a folha de contato do `skin_preview`). Só os formatos
 * que a Messages API da Anthropic aceita num bloco `image` (`image/jpeg`, `image/png`, `image/gif`, `image/webp`; conferido na
 * página `build-with-claude/vision`) — qualquer outro nem é construído, para o erro aparecer na ferramenta e não numa resposta 400.
 *
 * Igualdade pelo CONTEÚDO dos bytes (uma `ByteArray` num `data class` compararia por referência e quebraria a igualdade das
 * mensagens do histórico); o `toString` não imprime os bytes.
 */
class ToolImage(val mediaType: String, val bytes: ByteArray) {
    init {
        require(mediaType in MEDIA_TYPES) { "tipo de imagem sem suporte: $mediaType" }
    }

    override fun equals(other: Any?): Boolean = other is ToolImage && other.mediaType == mediaType && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * mediaType.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "ToolImage($mediaType, ${bytes.size} bytes)"

    companion object {
        val MEDIA_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

        /** Acima disto a imagem não vai ao modelo (o limite da API é 10 MB em base64; aqui sobra folga para o JSON e para outras imagens do histórico). */
        const val MAX_BYTES = 3 * 1024 * 1024
    }
}

/**
 * O que o USUÁRIO lê no painel de confirmação (TLS-02): gerado pelo app a partir dos argumentos já validados, nunca o texto que o
 * modelo escreveu sobre a própria ação.
 */
class ToolDescription(val summary: String, val preview: ToolPreview? = null)

/**
 * Uma ferramenta que guarda estado ENQUANTO uma mensagem do usuário está sendo atendida (os arquivos de uma skin que chega em lotes,
 * em memória) e precisa soltá-lo quando o turno acaba — com sucesso, erro ou cancelamento. Quem conduz a conversa
 * ([AgentController]) chama [turnEnded] de todas as ferramentas que a implementam.
 */
interface TurnScoped {
    /** Começou um novo turno de geração [generation]. */
    fun turnStarted(generation: Long) {}

    /** O turno [generation] acabou (com sucesso ou erro). */
    fun turnEnded(generation: Long) { turnEnded() }

    /** O turno acabou (com sucesso ou erro). */
    fun turnEnded() {}

    /**
     * O turno [generation] foi CANCELADO (o usuário tocou em Cancelar) ou a conversa foi limpa. Por padrão é o mesmo que [turnEnded]; quem tem um efeito
     * que só vale quando o turno termina bem (a navegação do `open_tab`) o descarta aqui.
     */
    fun turnCancelled(generation: Long) { turnCancelled() }

    /**
     * O turno foi CANCELADO (o usuário tocou em Cancelar) ou a conversa foi limpa. Por padrão é o mesmo que [turnEnded]; quem tem um efeito
     * que só vale quando o turno termina bem (a navegação do `open_tab`) o descarta aqui.
     */
    fun turnCancelled() = turnEnded()
}

/**
 * Como desfazer uma ação: outra chamada de ferramenta, guardada como DADO (nome + argumentos) e não como função — assim o registro
 * de ações pode ser persistido (fase 1B) e o "Desfazer" passa pela mesma política e pelo mesmo painel de qualquer outra chamada.
 */
data class UndoCall(val toolName: String, val argsJson: String)

/**
 * O que uma ferramenta devolve. [content] vai ao modelo (depois do filtro de segredos e do corte de tamanho, no [ToolExecutor]).
 * [isError] = a ferramenta falhou de um jeito que o modelo pode corrigir. [image] é opcional (a folha de contato de uma skin): vai ao
 * modelo num bloco `image` do `tool_result`, mas só se o modelo escolhido aceitar imagem — o adaptador do provedor decide — e a tela a
 * mostra no cartão da ferramenta de qualquer jeito.
 */
class ToolOutput(
    val content: String,
    val isError: Boolean = false,
    val undo: UndoCall? = null,
    val image: ToolImage? = null,
) {
    companion object {
        fun ok(content: String, undo: UndoCall? = null, image: ToolImage? = null) = ToolOutput(content, isError = false, undo = undo, image = image)
        fun error(content: String, image: ToolImage? = null) = ToolOutput(content, isError = true, image = image)
    }
}

/**
 * Falha esperada de uma ferramenta: a [message] é segura para o modelo ler ("a skin não existe", "o arquivo passa de 64 KB").
 * Qualquer OUTRA exceção é tratada como defeito do app e o modelo só recebe o nome da classe.
 */
class ToolFailure(message: String) : Exception(message)

/**
 * Marca o único método que executa uma ferramenta de verdade: [AgentTool.run]. O compilador recusa quem o chamar (ou sobrescrever) sem
 * o opt-in explícito, e o `AgentIsolationTest` confere no código-fonte que o opt-in de CHAMADA só existe no [ToolExecutor] — o único
 * caminho até `run` é o que passa pela política e pela confirmação (D2). Quem implementa uma ferramenta anota o `override` com
 * `@PolicyGated` (propagar o requisito); quem a chama é o [ToolExecutor].
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "AgentTool.run só pode ser chamado pelo ToolExecutor, depois da política e da confirmação (D2).",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class PolicyGated

/**
 * Uma ferramenta que o modelo pode pedir. É código DO APP (não do modelo): o modelo só escolhe o nome e preenche os argumentos.
 * Nada aqui toca em `android.*` — o que precisa de Context entra pelo construtor da implementação (fase 1B).
 */
interface AgentTool {
    /** `^[a-zA-Z0-9_-]{1,128}$` (a regra da API da Anthropic); o [ToolRegistry] confere no registro. */
    val name: String

    /** O que faz, quando usar e quando NÃO usar, o que cada argumento significa — é o que o modelo lê para escolher a ferramenta. */
    val description: String

    /**
     * O nome que o USUÁRIO lê nos Ajustes e nos cartões do chat ("Pré-visualizar skin"), no idioma da interface — o [name] é do modelo.
     * Avaliado a cada uso (uma chamada a `tr`), então acompanha a troca de idioma.
     */
    val label: String get() = name

    /** Esquema dos argumentos: um objeto no subconjunto de JSON Schema que o [ToolSchema] entende. */
    val inputSchema: JSONObject

    val risk: ToolRisk

    /** Tempo máximo de [run] (TLS-06). A espera pela confirmação do usuário NÃO conta. */
    val timeoutMs: Long get() = DEFAULT_TIMEOUT_MS

    /** Teto do texto devolvido ao modelo; o que passar é cortado com um aviso. */
    val maxResultChars: Int get() = DEFAULT_MAX_RESULT_CHARS

    /**
     * O resumo do que vai acontecer, para o painel de confirmação — só é chamado nas ferramentas que pedem permissão, com os
     * argumentos já validados. O padrão lista os argumentos; quem altera algo deve sobrescrever com uma frase própria (e a prévia).
     */
    suspend fun describe(args: JSONObject, ctx: ToolContext): ToolDescription = ToolDescription(genericSummary(name, args))

    /** Executa. Só o [ToolExecutor] chama (ver [PolicyGated]); os [args] já passaram pelo esquema. */
    @PolicyGated
    suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val DEFAULT_MAX_RESULT_CHARS = 16_000
    }
}

/** O mínimo que o modelo e os motores precisam conhecer de uma ferramenta: sem o risco e sem o `run`. */
class ToolDefinition(val name: String, val description: String, val inputSchema: JSONObject)

/** "nome(arg=valor, …)" com cada valor cortado — o resumo padrão de uma ferramenta que não escreveu o seu. */
internal fun genericSummary(name: String, args: JSONObject): String {
    val parts = ArrayList<String>()
    val keys = args.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val raw = args.opt(key)?.toString().orEmpty().replace('\n', ' ')
        val shown = if (raw.length > SUMMARY_VALUE_CHARS) raw.take(SUMMARY_VALUE_CHARS) + "…" else raw
        parts += "$key=$shown"
    }
    return if (parts.isEmpty()) name else "$name(${parts.joinToString(", ")})"
}

private const val SUMMARY_VALUE_CHARS = 60
