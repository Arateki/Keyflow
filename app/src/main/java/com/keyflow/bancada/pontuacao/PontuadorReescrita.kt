package com.keyflow.bancada.pontuacao

import com.keyflow.bancada.corpus.Caso

/**
 * `reescrita` (BPT-03): o modelo obedece um comando sobre um texto (encurtar, corrigir, tornar formal). Quatro critérios, pesos do
 * design §3: **fatos** (0,4) — fração de `fatosObrigatorios` presentes; **limite** (0,2) — o tamanho (`maxCaracteres` ou `maxPalavras`)
 * e nenhum termo de `proibidos` (é onde o "tom" entra: só como lista de termos proibidos); **sem invenção** (0,2) — todo número e nome
 * próprio da saída aparece na entrada (ou em `permitidos`); **idioma** (0,2) — por palavras funcionais. Não há nota de estilo.
 */
object PontuadorReescrita : Pontuador {
    const val FATOS = "fatos"
    const val LIMITE = "limite"
    const val SEM_INVENCAO = "semInvencao"
    const val IDIOMA = "idioma"

    private val PESOS = mapOf(FATOS to 0.4, LIMITE to 0.2, SEM_INVENCAO to 0.2, IDIOMA to 0.2)

    override fun pontuar(caso: Caso, saida: String): Pontuacao {
        if (saida.isBlank()) return Pontuacao.zero(Marca.VAZIA)
        val marcas = LinkedHashSet<Marca>()
        val textoNormalizado = " ${Normalizacao.texto(saida)} "

        val fatos = caso.lista("fatosObrigatorios")
        val fatosPresentes = if (fatos.isEmpty()) 1.0 else fatos.count { " ${Normalizacao.texto(it)} " in textoNormalizado }.toDouble() / fatos.size

        val verificacoes = ArrayList<Boolean>()
        val maxCaracteres = caso.bruto.optInt("maxCaracteres", -1)
        val maxPalavras = caso.bruto.optInt("maxPalavras", -1)
        if (maxCaracteres > 0) verificacoes += saida.trim().length <= maxCaracteres
        if (maxPalavras > 0) verificacoes += Normalizacao.fichas(saida).size <= maxPalavras
        val proibidos = caso.lista("proibidos")
        if (proibidos.isNotEmpty()) verificacoes += proibidos.none { " ${Normalizacao.texto(it)} " in textoNormalizado }
        val limite = if (verificacoes.isEmpty()) 1.0 else verificacoes.count { it }.toDouble() / verificacoes.size
        if (limite < 1.0) marcas += Marca.FORA_DO_LIMITE

        val semInvencao = fracaoFundamentada(caso, saida)
        if (semInvencao < 1.0) marcas += Marca.INVENCAO

        val detectado = Idiomas.detectar(saida)
        val idioma = when {
            detectado == null -> {
                marcas += Marca.IDIOMA_INCERTO
                1.0
            }
            detectado == caso.idioma -> 1.0
            else -> {
                marcas += Marca.IDIOMA_ERRADO
                0.0
            }
        }
        return Pontuacao.ponderada(
            mapOf(FATOS to fatosPresentes, LIMITE to limite, SEM_INVENCAO to semInvencao, IDIOMA to idioma),
            PESOS,
            marcas,
        )
    }

    /** A fração dos números e nomes próprios da saída que estão na entrada; 1 se a saída não tem nenhum. */
    private fun fracaoFundamentada(caso: Caso, saida: String): Double {
        val conhecidas = HashSet<String>()
        conhecidas += Normalizacao.palavras(caso.entrada)
        caso.lista("permitidos").forEach { conhecidas += Normalizacao.palavras(it) }
        conhecidas += REGISTRO_FORMAL
        val verificadas = Normalizacao.fichas(saida).filter { ficha ->
            ficha.normalizada.any { it.isDigit() } || (ficha.maiuscula && !ficha.inicioDeFrase && ficha.original.any { it.isLetter() })
        }
        if (verificadas.isEmpty()) return 1.0
        return verificadas.count { it.normalizada in conhecidas }.toDouble() / verificadas.size
    }

    /** Palavras de tratamento e despedida que um texto "mais formal" acrescenta legitimamente. */
    private val REGISTRO_FORMAL = setOf(
        "senhor", "senhora", "prezado", "prezada", "atenciosamente", "cordialmente", "estimado", "estimada", "sr", "sra",
        "dear", "sir", "madam", "regards", "sincerely", "best", "thanks", "mr", "mrs", "ms",
        "señor", "señora", "estimados", "atentamente", "saludos", "cordial",
    )
}

/**
 * Idioma pelo que se acha de palavras funcionais (artigos, preposições, pronomes) de cada língua — só as que NÃO existem nas outras,
 * porque `de`, `que`, `para`, `no` aparecem em duas. Heurística honesta: textos curtos podem não ter palavra nenhuma (aí o resultado é
 * "incerto", e o pontuador não pune).
 */
object Idiomas {
    private val PT = setOf(
        "da", "do", "das", "dos", "não", "uma", "com", "os", "as", "em", "na", "nas", "são", "você", "seu", "sua", "mais", "mas", "ao",
        "pelo", "pela", "meu", "minha", "isso", "esse", "essa", "ele", "ela", "também", "já", "muito", "às", "à", "um", "é", "foi", "pra", "e",
    )
    private val EN = setOf(
        "the", "and", "of", "to", "is", "are", "was", "for", "with", "that", "this", "it", "you", "your", "on", "be", "have", "has", "not", "but",
        "we", "they", "at", "from", "by", "an", "will", "can", "please", "our", "my", "i", "in", "he", "she", "his", "her", "there", "what", "when",
    )
    private val ES = setOf(
        "el", "los", "las", "del", "y", "un", "una", "es", "son", "fue", "con", "su", "sus", "al", "pero", "más", "muy", "lo", "le", "mi", "usted",
        "están", "también", "ya", "esto", "este", "yo", "tú", "hola", "gracias", "nuestro", "qué",
    )

    /** `pt`, `en` ou `es`; `null` quando nenhuma palavra funcional aparece ou há empate no topo. */
    fun detectar(texto: String): String? {
        val palavras = Normalizacao.palavras(texto)
        val contagem = listOf("pt" to PT, "en" to EN, "es" to ES).map { (id, lista) -> id to palavras.count { it in lista } }
        val maior = contagem.maxOf { it.second }
        if (maior == 0) return null
        return contagem.filter { it.second == maior }.singleOrNull()?.first
    }
}
