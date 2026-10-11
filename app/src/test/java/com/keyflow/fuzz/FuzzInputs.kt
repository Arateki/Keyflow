package com.keyflow.fuzz

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import org.json.JSONObject
import kotlin.random.Random

/**
 * Entradas para o fuzzing: JSON gerado (válido), JSON mutado (cortado, com caracteres trocados, inseridos ou apagados),
 * aninhamento fundo e lixo puro. A mistura é o que acha o que nenhum exemplo escrito à mão cobre.
 */
internal object FuzzInputs {

    fun json(random: Random, depth: Int = 4): String = when (if (depth <= 0) random.nextInt(4) else random.nextInt(6)) {
        0 -> JSONObject.quote(text(random))
        1 -> listOf("0", "-1", "1e309", "3.14", Long.MAX_VALUE.toString(), "-0", "1e-400", random.nextInt().toString()).random(random)
        2 -> listOf("true", "false", "null").random(random)
        3 -> JSONObject.quote(listOf("mic", "key", "grid", "oauth", "api_key", "orb", "idle", "").random(random))
        4 -> (0 until random.nextInt(5)).joinToString(",", "{", "}") { "${JSONObject.quote(key(random))}:${json(random, depth - 1)}" }
        else -> (0 until random.nextInt(5)).joinToString(",", "[", "]") { json(random, depth - 1) }
    }

    /** As chaves que os nossos formatos usam, misturadas com outras: o fuzzing chega aos ramos de verdade. */
    private fun key(random: Random) = listOf(
        "type", "value", "mirrored", "collapsed", "expanded", "buttons", "key", "keyCode", "label", "gridId", "mode", "prefix",
        "id", "name", "rows", "cols", "cells", "accessToken", "refreshToken", "expiresAtMs", "scopes", "schemaVersion", "orb",
        "keyboard", "fps", "states", "text", "x", "", "\u0000",
    ).random(random)

    private fun text(random: Random): String = buildString {
        repeat(random.nextInt(12)) {
            append(listOf("a", "é", "\"", "\\", "\n", "\u0000", "{", "}", "[", "]", ":", ",", "😀", "\uD800").random(random))
        }
    }

    fun mutate(random: Random, input: String): String {
        val chars = StringBuilder(input)
        repeat(1 + random.nextInt(4)) {
            if (chars.isEmpty()) return@repeat
            val at = random.nextInt(chars.length)
            when (random.nextInt(4)) {
                0 -> chars.deleteCharAt(at)
                1 -> chars.insert(at, "{}[]\":,\\".random(random))
                2 -> chars.setCharAt(at, (random.nextInt(0x20, 0x7f)).toChar())
                else -> chars.setLength(at)
            }
        }
        return chars.toString()
    }

    /** Uma entrada de qualquer um dos tipos acima. */
    val anyText: Arb<String> = arbitrary { rs ->
        val r = rs.random
        when (r.nextInt(5)) {
            0 -> json(r)
            1 -> mutate(r, json(r))
            2 -> "[".repeat(r.nextInt(1, 3_000)) + "]".repeat(r.nextInt(0, 3_000))
            3 -> "{\"a\":".repeat(r.nextInt(1, 2_000))
            else -> String(CharArray(r.nextInt(64)) { r.nextInt(0, 0xFFFF).toChar() })
        }
    }
}
