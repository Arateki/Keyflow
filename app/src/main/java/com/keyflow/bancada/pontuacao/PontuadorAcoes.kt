package com.keyflow.bancada.pontuacao

import com.keyflow.bancada.corpus.Caso
import org.json.JSONArray
import org.json.JSONObject

/**
 * `acoes` (BPT-06): pedido → ferramenta + argumentos do agente. O modelo responde **uma** chamada `{"nome": "...", "args": {...}}`
 * (ou `{"recusa": "..."}` quando nenhuma ferramenta serve). Nota = **ferramenta** certa (0,4) + **argumentos** exatos (0,6; JSON
 * canônico, só vale com a ferramenta certa). Caso sem ferramenta aplicável (`esperado` nulo) espera recusa: recusa ou saída vazia = 1;
 * qualquer chamada (espúria) = 0; JSON ilegível = 0. Mais de uma chamada = 0 (o teste pede uma).
 */
object PontuadorAcoes : Pontuador {
    const val FERRAMENTA = "ferramenta"
    const val ARGUMENTOS = "argumentos"

    override fun pontuar(caso: Caso, saida: String): Pontuacao {
        val esperado = caso.esperado
        val recusaEsperada = esperado == null || esperado == JSONObject.NULL
        if (saida.isBlank()) return if (recusaEsperada) Pontuacao(1.0, marcas = setOf(Marca.RECUSA_CORRETA)) else Pontuacao.zero(Marca.VAZIA)

        val extracao = ExtratorJson.extrair(saida)
        if (extracao.valores.isEmpty()) return Pontuacao.zero(Marca.JSON_ILEGIVEL)
        val marcas = LinkedHashSet<Marca>()
        when (extracao.forma) {
            ExtratorJson.Forma.CERCA -> marcas += Marca.CERCA_DE_CODIGO
            ExtratorJson.Forma.EM_VOLTA -> marcas += Marca.TEXTO_EM_VOLTA
            else -> Unit
        }
        val chamadas = objetos(extracao.valores)
        // Saída maior que o limite de leitura: o resto não foi visto, então não se garante que haja uma só chamada.
        if (extracao.truncada) return Pontuacao.zero(*(marcas + Marca.MULTIPLAS_CHAMADAS).toTypedArray())
        if (chamadas.size != 1) return Pontuacao.zero(*(marcas + (if (chamadas.isEmpty()) Marca.JSON_ILEGIVEL else Marca.MULTIPLAS_CHAMADAS)).toTypedArray())
        val chamada = chamadas.single()
        val nome = chamada.opt("nome") as? String
        val recusou = nome == null && chamada.has("recusa")

        if (recusaEsperada) {
            return when {
                recusou -> Pontuacao(1.0, marcas = marcas + Marca.RECUSA_CORRETA)
                nome != null -> Pontuacao.zero(*(marcas + Marca.CHAMADA_ESPURIA).toTypedArray())
                else -> Pontuacao.zero(*(marcas + Marca.JSON_ILEGIVEL).toTypedArray())
            }
        }
        val chamadaEsperada = esperado as? JSONObject ?: error("caso ${caso.id}: `esperado` do `acoes` é {nome, args} ou nulo")
        if (recusou) return Pontuacao.zero(*(marcas + Marca.RECUSA_INDEVIDA).toTypedArray())
        if (nome == null) return Pontuacao.zero(*(marcas + Marca.JSON_ILEGIVEL).toTypedArray())

        val ferramentaCerta = nome.trim() == chamadaEsperada.getString("nome")
        // `args` ausente vale {}; presente, precisa ser objeto (`null`, texto ou array são argumentos inválidos).
        val argsDaChamada = if (chamada.has("args")) chamada.optJSONObject("args") else JSONObject()
        val argumentosCertos = ferramentaCerta && argsDaChamada != null &&
            Canonico.de(argsDaChamada) == Canonico.de(chamadaEsperada.optJSONObject("args") ?: JSONObject())
        if (!ferramentaCerta) marcas += Marca.FERRAMENTA_ERRADA else if (!argumentosCertos) marcas += Marca.ARGUMENTOS_ERRADOS
        return Pontuacao.ponderada(
            mapOf(FERRAMENTA to if (ferramentaCerta) 1.0 else 0.0, ARGUMENTOS to if (argumentosCertos) 1.0 else 0.0),
            mapOf(FERRAMENTA to 0.4, ARGUMENTOS to 0.6),
            marcas,
        )
    }

    /** Os objetos de nível mais alto da resposta; um array de chamadas conta cada elemento objeto. */
    private fun objetos(valores: List<Any>): List<JSONObject> = valores.flatMap { valor ->
        when (valor) {
            is JSONObject -> listOf(valor)
            is JSONArray -> (0 until valor.length()).mapNotNull { valor.optJSONObject(it) }
            else -> emptyList()
        }
    }
}
