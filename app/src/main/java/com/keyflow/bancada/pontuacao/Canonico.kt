package com.keyflow.bancada.pontuacao

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/**
 * O JSON canônico: chaves em ordem alfabética, números sem zeros à direita (`1.0` = `1`), textos aparados, sem espaço. Duas respostas
 * "iguais para quem lê" viram o mesmo texto — é o que o `acoes` compara em vez de uma lista de chaves e valores à mão.
 */
object Canonico {
    fun de(valor: Any?): String = StringBuilder().also { escrever(valor, it) }.toString()

    private fun escrever(valor: Any?, saida: StringBuilder) {
        when (valor) {
            null, JSONObject.NULL -> saida.append("null")
            is JSONObject -> {
                saida.append('{')
                valor.keys().asSequence().toList().sorted().forEachIndexed { i, chave ->
                    if (i > 0) saida.append(',')
                    saida.append(JSONObject.quote(chave)).append(':')
                    escrever(valor.opt(chave), saida)
                }
                saida.append('}')
            }
            is JSONArray -> {
                saida.append('[')
                for (i in 0 until valor.length()) {
                    if (i > 0) saida.append(',')
                    escrever(valor.opt(i), saida)
                }
                saida.append(']')
            }
            is Boolean -> saida.append(valor)
            is Number -> saida.append(numero(valor))
            else -> saida.append(JSONObject.quote(valor.toString().trim()))
        }
    }

    /** O número em forma única: inteiro sem ponto, decimal sem zeros à direita. */
    fun numero(valor: Number): String = try {
        BigDecimal(valor.toString()).stripTrailingZeros().toPlainString()
    } catch (e: NumberFormatException) {
        valor.toString()
    }
}
