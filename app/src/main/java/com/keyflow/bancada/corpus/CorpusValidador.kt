package com.keyflow.bancada.corpus

import com.keyflow.bancada.redacao.RedatorDeSegredos

/**
 * A conferência de integridade do corpus (BCP-02/03/04/07), sem ler o resto do app: devolve a lista de problemas (vazia = íntegro).
 * O `CorpusIntegrityTest` a roda contra o corpus real e contra o de brinquedo, e prova que ela pega o byte alterado, a licença GPL
 * e o id repetido.
 */
object CorpusValidador {
    /** Licenças compatíveis com repositório público e app comercial (BCP-02). Nada GPL/AGPL/LGPL nem "não comercial". */
    val LICENCAS_PERMITIDAS: Set<String> = setOf("Apache-2.0", "CC0-1.0", "CC-BY-4.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Unlicense")

    /** O que a licença não pode conter, mesmo que alguém a acrescente à lista por engano. */
    private val PROIBIDAS = Regex("(?i)(^|[^a-z])(a?gpl|lgpl|nc)([^a-z]|$)|non-?commercial|n[aã]o comercial")

    /** O corpus inteiro cabe em 3 MB no APK (BCP-04). */
    const val ORCAMENTO_BYTES: Long = 3L * 1024 * 1024

    val IDIOMAS: Set<String> = setOf("pt", "en", "es")

    fun validar(fonte: FonteDoCorpus, filtro: RedatorDeSegredos = RedatorDeSegredos(), orcamentoBytes: Long = ORCAMENTO_BYTES): List<String> {
        val problemas = ArrayList<String>()
        val manifesto = try {
            CorpusLoader.lerManifesto(fonte)
        } catch (e: CorpusInvalidoException) {
            return listOf(e.message ?: "manifesto ilegível")
        }
        var total = fonte.ler(CorpusLoader.MANIFESTO)?.size?.toLong() ?: 0L
        val noManifesto = HashSet<String>()
        for (arquivo in manifesto.arquivos) {
            if (!noManifesto.add(arquivo.caminho)) problemas += "${arquivo.caminho}: repetido no manifesto"
            if (arquivo.licenca !in LICENCAS_PERMITIDAS || PROIBIDAS.containsMatchIn(arquivo.licenca)) {
                problemas += "${arquivo.caminho}: licença fora da lista permitida (${arquivo.licenca})"
            }
            if (arquivo.origem.isBlank()) problemas += "${arquivo.caminho}: sem origem"
            if (arquivo.autor.isBlank()) problemas += "${arquivo.caminho}: sem autor"
            val bytes = fonte.ler(arquivo.caminho)
            if (bytes == null) {
                problemas += "${arquivo.caminho}: arquivo do manifesto ausente"
                continue
            }
            total += bytes.size
            if (sha256Hex(bytes) != arquivo.sha256) problemas += "${arquivo.caminho}: o SHA-256 não bate com o manifesto"
        }
        for (caminho in fonte.listar()) {
            if (caminho != CorpusLoader.MANIFESTO && caminho !in noManifesto) problemas += "$caminho: arquivo fora do manifesto"
        }
        if (total > orcamentoBytes) problemas += "o corpus tem $total bytes; o orçamento é $orcamentoBytes"

        val ids = HashSet<String>()
        for (arquivo in manifesto.arquivos.filter { it.caminho.endsWith(".json") }) {
            val bytes = fonte.ler(arquivo.caminho) ?: continue
            val teste = try {
                CorpusLoader.lerTeste(arquivo.caminho, bytes)
            } catch (e: CorpusInvalidoException) {
                problemas += e.message ?: "${arquivo.caminho} ilegível"
                continue
            }
            problemas += validarTeste(arquivo.caminho, teste, ids, filtro)
        }
        return problemas
    }

    private fun validarTeste(caminho: String, teste: TesteDef, ids: MutableSet<String>, filtro: RedatorDeSegredos): List<String> {
        val problemas = ArrayList<String>()
        fun erro(texto: String) {
            problemas += "$caminho: $texto"
        }
        if (caminho.removeSuffix(".json").substringAfterLast('/') != teste.id) erro("o id do teste (${teste.id}) não é o nome do arquivo")
        if (teste.versao < 1) erro("versão do teste inválida (${teste.versao})")
        if (teste.descricao.isBlank()) erro("sem descrição")
        if (TesteDef.PLACEHOLDER_ENTRADA !in teste.prompt.user) erro("o prompt do usuário não tem ${TesteDef.PLACEHOLDER_ENTRADA}")
        if (teste.casos.isEmpty()) erro("sem casos")
        val textosDoPrompt = listOfNotNull(teste.prompt.system, teste.prompt.user, teste.ferramentas)
        if (textosDoPrompt.any(filtro::containsSecret)) erro("o prompt contém algo que o filtro de segredos removeria")
        for (caso in teste.casos) {
            if (!ids.add(caso.id)) erro("id de caso repetido: ${caso.id}")
            if (caso.id.isBlank()) erro("caso sem id")
            if (caso.idioma !in IDIOMAS) erro("${caso.id}: idioma desconhecido (${caso.idioma})")
            if (caso.entrada.isBlank()) erro("${caso.id}: entrada vazia")
            if (caso.maxTokensSaida <= 0) erro("${caso.id}: maxTokensSaida precisa ser positivo")
            if (caso.tokensEntrada < 0) erro("${caso.id}: tokensEntrada negativo")
            if (filtro.containsSecret(caso.bruto.toString())) erro("${caso.id}: contém algo que o filtro de segredos removeria (BCP-07)")
        }
        return problemas
    }
}
