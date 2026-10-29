package com.keyflow.speech.frontend

import java.text.Normalizer
import org.json.JSONArray

/** Port do UnicodeProcessor e chunk_text oficiais (MIT, Supertone; aviso em app/licenses/MIT_supertonic.txt). */
class SupertonicText(private val index: IntArray) {
    fun ids(text: String): LongArray = normalize(text).codePoints().toArray().map { code ->
        val value = code and 0xffff // O frontend oficial converte os codepoints para uint16.
        require(value < index.size) { "índice Unicode incompleto" }
        index[value].toLong()
    }.toLongArray()

    companion object {
        fun parse(json: String): SupertonicText {
            val array = JSONArray(json)
            require(array.length() > 0) { "índice Unicode vazio" }
            return SupertonicText(IntArray(array.length()) { array.getInt(it) })
        }

        fun normalize(input: String): String {
            var text = Normalizer.normalize(input, Normalizer.Form.NFKD)
            text = text.codePoints().toArray().filterNot { code ->
                code in 0x1f300..0x1f64f || code in 0x1f680..0x1f6ff || code in 0x1f700..0x1faff ||
                    code in 0x2600..0x27bf || code in 0x1f1e6..0x1f1ff
            }.map { String(Character.toChars(it)) }.joinToString("")
            for ((from, to) in replacements) text = text.replace(from, to)
            text = text.replace(Regex("[♥☆♡©\\\\]"), "")
                .replace("@", " at ").replace("e.g.,", "for example, ").replace("i.e.,", "that is, ")
                .replace(Regex(" ([,.!?;:'])"), "$1")
                .replace(Regex("\"+"), "\"").replace(Regex("'+"), "'")
                .replace(Regex("[\\s\\p{Z}\\u0085\\u001c-\\u001f]+"), " ").trim()
            if (!Regex("[.!?;:,'\")\\]\\}…。」』】〉》›»]$").containsMatchIn(text)) text += "."
            return "<pt>$text</pt>"
        }

        /** Limite e separação de sentenças do exemplo oficial; a voz é sempre portuguesa nesta etapa. */
        fun chunks(text: String, maxLength: Int = 300): List<String> {
            val boundary = Regex("(?<!Mr\\.)(?<!Mrs\\.)(?<!Ms\\.)(?<!Dr\\.)(?<!Prof\\.)(?<!Sr\\.)(?<!Jr\\.)(?<!Ph\\.D\\.)(?<!etc\\.)(?<!e\\.g\\.)(?<!i\\.e\\.)(?<!vs\\.)(?<!Inc\\.)(?<!Ltd\\.)(?<!Co\\.)(?<!Corp\\.)(?<!St\\.)(?<!Ave\\.)(?<!Blvd\\.)(?<!\\b[A-Z]\\.)(?<=[.!?])\\s+")
            return text.trim().split(Regex("\\n\\s*\\n+")).filter { it.isNotBlank() }.flatMap { paragraph ->
                val out = mutableListOf<String>()
                var current = ""
                for (sentence in paragraph.trim().split(boundary)) {
                    if (current.length + sentence.length + 1 <= maxLength) {
                        current += (if (current.isEmpty()) "" else " ") + sentence
                    } else {
                        if (current.isNotBlank()) out += current.trim()
                        current = sentence
                    }
                }
                if (current.isNotBlank()) out += current.trim()
                out
            }
        }

        private val replacements = mapOf("–" to "-", "‑" to "-", "—" to "-", "_" to " ",
            "“" to "\"", "”" to "\"", "‘" to "'", "’" to "'", "´" to "'", "`" to "'",
            "[" to " ", "]" to " ", "|" to " ", "/" to " ", "#" to " ", "→" to " ", "←" to " ")
    }
}
