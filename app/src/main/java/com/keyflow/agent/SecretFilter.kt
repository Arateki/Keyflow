package com.keyflow.agent

/**
 * O filtro de segredos (TLS-03, D5): defesa em profundidade. Nenhuma ferramenta tem acesso a segredo, mas se um dia um aparecer num
 * resultado (um erro que ecoa um cabeçalho, um arquivo de skin que alguém colou com uma chave), ele não chega ao modelo nem ao registro
 * de ações. Passa por AQUI todo texto que sai do app para o modelo (resultado de ferramenta, mensagem do usuário) e tudo que entra no
 * registro. Puro (JVM).
 *
 * Remove, trocando por [MARKER]:
 *  - blocos PEM inteiros (`-----BEGIN … -----` até o `END`, ou até o fim do texto se vier cortado);
 *  - JWT (`eyJ….eyJ….assinatura`), `Bearer <token>`;
 *  - chaves de API pelo formato: `sk-…` (OpenAI e Anthropic, `sk-ant-…`), `AIza…` (Google), `gsk_…` (Groq), `xai-…` (xAI),
 *    `sk_<hex>` (ElevenLabs), `ghp_…`/`github_pat_…` (GitHub), `AKIA…` (id de acesso da AWS) — formatos públicos dos provedores; é
 *    uma heurística, não uma garantia;
 *  - **qualquer valor igual a um segredo guardado** ([registeredSecrets]: a chave do cofre, os tokens do login) — a garantia de verdade
 *    para as credenciais do próprio app, que a heurística de formato não cobre se o provedor mudar o formato.
 *
 * Nunca imprime, loga nem põe um segredo em exceção: o único jeito de ele aparecer é na entrada. Texto comum passa intacto — os
 * padrões exigem prefixo + tamanho mínimo e não casam no meio de uma palavra (`desk-organizer-…`, `task-…`).
 *
 * Se a lista de segredos registrados não puder ser lida (o cofre falhou), o texto inteiro é trocado por [WITHHELD]: falha fechado.
 *
 * Limites honestos: um segredo partido em dois pedaços, codificado (base64, URL) ou com um caractere trocado não é reconhecido; e um
 * valor registrado com menos de [MIN_SECRET_LENGTH] caracteres é ignorado, senão um "segredo" curto mutilaria texto comum.
 */
class SecretFilter(
    /** Os valores secretos de agora (cofre + login), lidos A CADA uso: uma chave trocada nos Ajustes já vale na chamada seguinte. */
    private val registeredSecrets: () -> Collection<String> = { emptyList() },
) {

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        // Valores registrados primeiro: são exatos e podem ter qualquer forma. Os mais longos antes, para um segredo que contém outro
        // ser trocado inteiro. Se o cofre não puder ser lido, NADA passa: sem a lista não dá para garantir que o texto está limpo.
        val secrets = try {
            registeredSecrets().asSequence().filter { it.length >= MIN_SECRET_LENGTH }.distinct().sortedByDescending { it.length }.toList()
        } catch (e: Exception) {
            return WITHHELD
        }
        for (secret in secrets) {
            if (out.contains(secret)) out = out.replace(secret, MARKER)
        }
        for (pattern in PATTERNS) out = pattern.replace(out, MARKER)
        return out
    }

    /** O texto tem algo que [redact] trocaria? É o que recusa um ARGUMENTO com segredo (TLS-03: "nenhuma ferramenta aceita segredos"). */
    fun containsSecret(text: String): Boolean = redact(text) != text

    companion object {
        /** No lugar do segredo. Fixo e sem nenhum pedaço dele. */
        const val MARKER = "[segredo removido]"

        /** O que sai no lugar do texto inteiro quando o cofre não pôde ser lido (falha fechado). */
        const val WITHHELD = "[conteúdo omitido: não foi possível conferir se havia segredos]"

        /** Valores registrados menores que isto não entram na troca por valor (ver o limite no KDoc da classe). */
        const val MIN_SECRET_LENGTH = 8

        /** Não pode vir colado a uma letra/dígito/`_` antes (`task-…` não é `sk-…`). */
        private const val NOT_IN_WORD = "(?<![A-Za-z0-9_])"

        private val PATTERNS: List<Regex> = listOf(
            // Bloco PEM: do BEGIN ao END correspondente, ou até o fim se o texto foi cortado antes do END.
            Regex("-----BEGIN ([A-Z0-9 ]+)-----[\\s\\S]*?(?:-----END \\1-----|\\z)"),
            Regex("${NOT_IN_WORD}eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*"),
            Regex("${NOT_IN_WORD}Bearer\\s+[A-Za-z0-9._~+/=-]{16,}", RegexOption.IGNORE_CASE),
            Regex("${NOT_IN_WORD}sk-[A-Za-z0-9_-]{20,}"),
            Regex("${NOT_IN_WORD}AIza[A-Za-z0-9_-]{30,}"),
            Regex("${NOT_IN_WORD}gsk_[A-Za-z0-9]{20,}"),
            Regex("${NOT_IN_WORD}xai-[A-Za-z0-9]{20,}"),
            Regex("${NOT_IN_WORD}sk_[a-f0-9]{32,}"),
            Regex("${NOT_IN_WORD}(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})"),
            Regex("${NOT_IN_WORD}AKIA[0-9A-Z]{16}(?![A-Za-z0-9])"),
        )
    }
}
