package com.keyflow.bancada.corpus

import org.json.JSONObject

/** O que o teste precisa do modelo: texto → texto (`ReasoningEngine`), áudio → texto ou imagem → texto. P1 só roda [TEXTO]. */
enum class Capacidade { TEXTO, AUDIO, IMAGEM }

/**
 * Um caso do corpus (BCP-03): id estável, idioma, entrada, teto de tokens de saída e tamanho aproximado da entrada. O que cada
 * pontuador pede além disso (fatos obrigatórios, esquema, ferramenta esperada…) fica em [bruto], o JSON do caso como está no arquivo.
 */
class Caso(
    val id: String,
    /** `pt`, `en` ou `es`. */
    val idioma: String,
    /** A mensagem do usuário que o prompt do teste recebe no lugar de `{entrada}`. */
    val entrada: String,
    val maxTokensSaida: Int,
    /** Estimativa (caracteres ÷ 4, rotulada assim): só decide "não se aplica" quando o modelo tem janela menor (BNC-03). */
    val tokensEntrada: Int,
    val bruto: JSONObject,
) {
    /** O esperado como está no arquivo: texto, objeto, `JSONObject.NULL` (recusa) ou `null` quando o campo não existe. */
    val esperado: Any? get() = if (bruto.has("esperado")) bruto.get("esperado") else null

    /** Texto do campo [chave], ou `null` se não existe ou não é texto. */
    fun texto(chave: String): String? = bruto.optString(chave, "").takeIf { bruto.opt(chave) is String }

    /** Lista de textos do campo [chave] (vazia se não existe). */
    fun lista(chave: String): List<String> {
        val array = bruto.optJSONArray(chave) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it, "").takeIf { s -> array.opt(it) is String } }
    }
}

/** O prompt de um teste: faz parte da versão do teste, nunca é montado ad hoc (BCP-03). `{entrada}` é o único campo do caso que entra. */
data class Prompt(val system: String?, val user: String)

/** O prompt pronto para um caso: o que o motor recebe. */
data class PromptMontado(val system: String?, val user: String)

/** Um teste do corpus: o arquivo `<teste>.json` (BCP-01). */
class TesteDef(
    val id: String,
    val versao: Int,
    val capacidade: Capacidade,
    /** Uma linha: o que o teste mede (aparece na sub-aba, BNC-02). */
    val descricao: String,
    val prompt: Prompt,
    val casos: List<Caso>,
    /**
     * Só o `acoes`: as ferramentas como o app as enviaria (nome, descrição, esquema) num JSON COMPACTO guardado como texto — o texto exato
     * que entra no prompt no lugar de `{ferramentas}`, igual em qualquer aparelho (a ordem das chaves de um `JSONObject` não é).
     */
    val ferramentas: String? = null,
) {
    /** O prompt de [caso]: `{entrada}` e `{ferramentas}` substituídos, nada mais. */
    fun montar(caso: Caso): PromptMontado {
        fun preencher(modelo: String): String = modelo
            .replace(PLACEHOLDER_FERRAMENTAS, ferramentas.orEmpty())
            .replace(PLACEHOLDER_ENTRADA, caso.entrada)
        return PromptMontado(prompt.system?.let(::preencher), preencher(prompt.user))
    }

    companion object {
        const val PLACEHOLDER_ENTRADA = "{entrada}"
        const val PLACEHOLDER_FERRAMENTAS = "{ferramentas}"
    }
}

/** Uma linha do `manifesto.json`: arquivo, hash, de onde veio, quem escreveu e sob qual licença (BCP-01/02). */
data class ArquivoManifesto(val caminho: String, val sha256: String, val origem: String, val autor: String, val licenca: String)

data class Manifesto(val corpus: Int, val arquivos: List<ArquivoManifesto>)

/** O corpus carregado: a versão (entra em todo resultado), o manifesto e os testes por id. */
class Corpus(val versao: Int, val manifesto: Manifesto, val testes: Map<String, TesteDef>) {
    fun teste(id: String): TesteDef? = testes[id]
}
