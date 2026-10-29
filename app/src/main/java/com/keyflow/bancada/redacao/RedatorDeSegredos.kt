package com.keyflow.bancada.redacao

/**
 * O filtro de segredos da bancada (BON-03, BCP-07): tira de um texto chaves de API, tokens e blocos PEM antes de ele ir para o disco, para a exportação
 * ou para o corpus. **Mesmos padrões e mesmo comportamento do `com.keyflow.agent.SecretFilter`** — e de propósito uma cópia: o agente não vai no APK de
 * release (V1: "Em breve"; o `scripts/check-release-agent.py` recusa qualquer `com.keyflow.agent.*` no DEX) e a bancada roda no release, com a credencial
 * do usuário. Importar o `SecretFilter` aqui o traria junto. O `RedatorDeSegredosParidadeTest` compara os dois em dezenas de entradas e falha se um mudar sem o outro.
 *
 * Remove, trocando por [MARKER]: blocos PEM; JWT; `Bearer <token>`; chaves pelo formato (`sk-…`, `AIza…`, `gsk_…`, `xai-…`, `sk_<hex>`, `ghp_…`/`github_pat_…`,
 * `AKIA…`); e **qualquer valor igual a um segredo guardado** ([segredos]: lidos a CADA uso, para a chave trocada nos Ajustes já valer na chamada seguinte).
 * Se a lista de segredos não puder ser lida, o texto inteiro vira [WITHHELD] (falha fechado). Nunca imprime, loga nem põe um segredo em exceção.
 * Limites honestos: segredo partido em dois, codificado ou com um caractere trocado não é reconhecido, e valor registrado com menos de [MIN_SECRET_LENGTH]
 * caracteres é ignorado.
 */
class RedatorDeSegredos(private val segredos: () -> Collection<String> = { emptyList() }) {

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        val conhecidos = try {
            segredos().asSequence().filter { it.length >= MIN_SECRET_LENGTH }.distinct().sortedByDescending { it.length }.toList()
        } catch (e: Exception) {
            return WITHHELD
        }
        for (segredo in conhecidos) {
            if (out.contains(segredo)) out = out.replace(segredo, MARKER)
        }
        for (padrao in PADROES) out = padrao.replace(out, MARKER)
        return out
    }

    /** O texto tem algo que [redact] trocaria? */
    fun containsSecret(text: String): Boolean = redact(text) != text

    companion object {
        const val MARKER = "[segredo removido]"
        const val WITHHELD = "[conteúdo omitido: não foi possível conferir se havia segredos]"
        const val MIN_SECRET_LENGTH = 8

        private const val FORA_DE_PALAVRA = "(?<![A-Za-z0-9_])"

        private val PADROES: List<Regex> = listOf(
            Regex("-----BEGIN ([A-Z0-9 ]+)-----[\\s\\S]*?(?:-----END \\1-----|\\z)"),
            Regex("${FORA_DE_PALAVRA}eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*"),
            Regex("${FORA_DE_PALAVRA}Bearer\\s+[A-Za-z0-9._~+/=-]{16,}", RegexOption.IGNORE_CASE),
            Regex("${FORA_DE_PALAVRA}sk-[A-Za-z0-9_-]{20,}"),
            Regex("${FORA_DE_PALAVRA}AIza[A-Za-z0-9_-]{30,}"),
            Regex("${FORA_DE_PALAVRA}gsk_[A-Za-z0-9]{20,}"),
            Regex("${FORA_DE_PALAVRA}xai-[A-Za-z0-9]{20,}"),
            Regex("${FORA_DE_PALAVRA}sk_[a-f0-9]{32,}"),
            Regex("${FORA_DE_PALAVRA}(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})"),
            Regex("${FORA_DE_PALAVRA}AKIA[0-9A-Z]{16}(?![A-Za-z0-9])"),
        )
    }
}
