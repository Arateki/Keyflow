package com.keyflow.agent

import org.json.JSONException
import org.json.JSONObject

/** O que a leitura dos argumentos de uma chamada deu: um objeto validado, ou o motivo (em português, para o modelo) de não valer. */
sealed interface ParsedArgs {
    class Valid(val args: JSONObject) : ParsedArgs
    class Rejected(val reason: String) : ParsedArgs
}

/**
 * O catálogo de ferramentas: registra, lista as ativas (Ajustes), resolve por nome e **valida os argumentos contra o esquema ANTES de
 * qualquer `run`** (TLS-01). Quem executa é o [ToolExecutor]; o registro só entrega argumentos já conferidos ([ParsedArgs.Valid]).
 */
class ToolRegistry(tools: List<AgentTool> = emptyList()) {
    private val byName = LinkedHashMap<String, AgentTool>()

    init {
        tools.forEach(::register)
    }

    /**
     * Falha alto (e na hora do registro, não no meio de uma conversa) se o nome não serve para a API, se já existe, ou se o esquema
     * sai do subconjunto de [ToolSchema].
     */
    @Synchronized
    fun register(tool: AgentTool) {
        require(NAME.matches(tool.name)) { "nome de ferramenta inválido: \"${tool.name}\" (use letras, dígitos, _ e -, até 128)" }
        require(tool.name !in byName) { "ferramenta já registrada: ${tool.name}" }
        val problems = ToolSchema.check(tool.inputSchema)
        require(problems.isEmpty()) { "esquema inválido em ${tool.name}: ${problems.joinToString("; ")}" }
        byName[tool.name] = tool
    }

    @Synchronized
    operator fun get(name: String): AgentTool? = byName[name]

    @Synchronized
    fun all(): List<AgentTool> = byName.values.toList()

    /** As ferramentas que [isEnabled] deixa ligadas, na ordem do registro. */
    fun active(isEnabled: (String) -> Boolean): List<AgentTool> = all().filter { isEnabled(it.name) }

    /** O que vai ao modelo: nome, descrição e esquema — uma CÓPIA do esquema, para ninguém alterar o da ferramenta. */
    fun definitions(isEnabled: (String) -> Boolean): List<ToolDefinition> =
        active(isEnabled).map { ToolDefinition(it.name, it.description, JSONObject(it.inputSchema.toString())) }

    /**
     * Lê os [argsJson] que o modelo mandou e confere com o esquema de [tool]. Texto vazio vale `{}` (a API manda um `input_json_delta`
     * vazio para uma ferramenta sem argumentos). Recusa JSON inválido, o que não é objeto, tamanho acima de [maxChars] e aninhamento
     * fundo — este último ANTES de passar pelo parser, para um `[[[[…` do modelo não estourar a pilha.
     */
    fun parseArgs(tool: AgentTool, argsJson: String, maxChars: Int = MAX_ARGS_CHARS): ParsedArgs {
        val text = argsJson.trim().ifEmpty { "{}" }
        if (text.length > maxChars) return ParsedArgs.Rejected("os argumentos passam do limite de $maxChars caracteres")
        if (nestingDepth(text) > MAX_NESTING) return ParsedArgs.Rejected("os argumentos têm aninhamento demais (máximo $MAX_NESTING níveis)")
        if (!text.startsWith("{") || !text.endsWith("}")) return ParsedArgs.Rejected("os argumentos precisam ser um objeto JSON")
        val parsed = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return ParsedArgs.Rejected("os argumentos não são um JSON válido")
        }
        val errors = ToolSchema.validate(tool.inputSchema, parsed)
        if (errors.isNotEmpty()) return ParsedArgs.Rejected(errors.joinToString("; "))
        return ParsedArgs.Valid(parsed)
    }

    companion object {
        /** A regra da API da Anthropic para o nome de uma ferramenta (`define-tools`). */
        private val NAME = Regex("^[a-zA-Z0-9_-]{1,128}$")

        /** O maior argumento aceito de uma só vez: acima disto o modelo está despejando, não pedindo (SKL-06 aperta por ferramenta). */
        const val MAX_ARGS_CHARS = 1_000_000
        const val MAX_NESTING = 32

        /** A profundidade de `{`/`[` fora de texto — uma varredura simples, sem alocar a árvore. */
        internal fun nestingDepth(json: String): Int {
            var depth = 0
            var max = 0
            var inString = false
            var escaped = false
            for (c in json) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else when (c) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > max) max = depth
                    }
                    '}', ']' -> if (depth > 0) depth--
                }
            }
            return max
        }
    }
}
