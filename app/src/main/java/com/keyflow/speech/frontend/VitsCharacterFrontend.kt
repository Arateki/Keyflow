package com.keyflow.speech.frontend

/**
 * Frontend de caracteres do VITS, no molde do `offline-tts-character-frontend.cc` do sherpa-onnx
 * (FLOC-03). Minúsculas só no ASCII, como o `std::tolower` byte a byte de lá: o acento fica e o
 * `tokens.txt` traz as duas caixas com o mesmo id. `blank` entre os caracteres, corte da frase em
 * `. : ? !`, caractere fora do mapa é descartado. O normalizador de dígitos roda antes, porque o
 * vocabulário não tem dígitos e o sherpa os jogaria fora.
 */
class VitsCharacterFrontend(
    private val tokens: VitsTokens,
    private val addBlank: Boolean = true,
    private val useEosBos: Boolean = false,
) {
    fun sentences(text: String, normalize: Boolean = true): List<IntArray> {
        val prepared = if (normalize) {
            PortugueseSpeechNormalizer.normalize(text) { tokens.idOf(it) != null }
        } else {
            text
        }
        return encode(prepared)
    }

    private fun encode(text: String): List<IntArray> {
        val lower = buildString(text.length) {
            for (c in text) append(if (c in 'A'..'Z') c.lowercaseChar() else c)
        }
        val out = ArrayList<IntArray>()
        val cur = ArrayList<Int>()
        fun start() {
            cur.clear()
            if (useEosBos) cur += tokens.bosId
            if (addBlank) cur += tokens.blankId
        }
        start()
        for (c in lower) {
            val id = tokens.idOf(c)
            if (id != null) {
                cur += id
                if (addBlank) cur += tokens.blankId
            }
            if (c == '.' || c == ':' || c == '?' || c == '!') {
                if (useEosBos) cur += tokens.eosId
                out += cur.toIntArray()
                start()
            }
        }
        if (addBlank) {
            if (useEosBos) cur += tokens.eosId
            if (cur.size > 1 + if (useEosBos) 1 else 0) out += cur.toIntArray()
        } else if (cur.size > 1) {
            out += cur.toIntArray()
        }
        return out
    }

    companion object {
        fun parse(tokensText: String, addBlank: Boolean = true, useEosBos: Boolean = false): VitsCharacterFrontend =
            VitsCharacterFrontend(VitsTokens.parse(tokensText), addBlank, useEosBos)
    }
}

/** Mapa do `tokens.txt` do Coqui: especiais de lado, um caractere por id. */
class VitsTokens(
    val padId: Int,
    val eosId: Int,
    val bosId: Int,
    val blankId: Int,
    private val chars: Map<Char, Int>,
) {
    fun idOf(c: Char): Int? = chars[c]

    companion object {
        fun parse(text: String): VitsTokens {
            var pad = 0
            var eos = 1
            var bos = 2
            var blank = 3
            val chars = LinkedHashMap<Char, Int>()
            for (raw in text.split('\n')) {
                val line = raw.trimEnd('\r')
                if (line.isBlank()) continue
                val parts = line.trim().split(Regex("\\s+"))
                val (sym, idText) = if (parts.size == 1) " " to parts[0] else parts[0] to parts[1]
                val id = idText.toInt()
                when (sym) {
                    "<PAD>" -> pad = id
                    "<EOS>" -> eos = id
                    "<BOS>" -> bos = id
                    "<BLNK>" -> blank = id
                    else -> {
                        require(sym.length == 1) { "token que não é um caractere: $sym" }
                        chars.putIfAbsent(sym[0], id)
                    }
                }
            }
            return VitsTokens(pad, eos, bos, blank, chars)
        }
    }
}
