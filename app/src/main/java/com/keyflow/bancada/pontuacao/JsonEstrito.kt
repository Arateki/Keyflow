package com.keyflow.bancada.pontuacao

/**
 * Validador de JSON estrito (RFC 8259) para o trecho que o [ExtratorJson] vai entregar ao `org.json`, que é permissivo (aceita chaves sem
 * aspas, aspas simples, `;` como separador, vírgula sobrando…). Saída de modelo com essa sintaxe não é JSON válido e não pode valer como tal.
 */
internal object JsonEstrito {
    fun valido(texto: String): Boolean {
        val leitor = Leitor(texto)
        leitor.espacos()
        if (!leitor.valor(0)) return false
        leitor.espacos()
        return leitor.fim()
    }

    private class Leitor(val t: String) {
        var i = 0

        fun fim() = i == t.length

        fun espacos() {
            while (i < t.length && (t[i] == ' ' || t[i] == '\t' || t[i] == '\n' || t[i] == '\r')) i++
        }

        fun valor(profundidade: Int): Boolean {
            if (profundidade > ExtratorJson.PROFUNDIDADE_MAXIMA || i >= t.length) return false
            return when (t[i]) {
                '{' -> objeto(profundidade)
                '[' -> array(profundidade)
                '"' -> texto()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> numero()
            }
        }

        fun literal(l: String): Boolean {
            if (!t.startsWith(l, i)) return false
            i += l.length
            return true
        }

        fun objeto(p: Int): Boolean {
            i++
            espacos()
            if (i < t.length && t[i] == '}') { i++; return true }
            while (true) {
                espacos()
                if (i >= t.length || t[i] != '"' || !texto()) return false
                espacos()
                if (i >= t.length || t[i] != ':') return false
                i++
                espacos()
                if (!valor(p + 1)) return false
                espacos()
                if (i >= t.length) return false
                when (t[i++]) {
                    ',' -> continue
                    '}' -> return true
                    else -> return false
                }
            }
        }

        fun array(p: Int): Boolean {
            i++
            espacos()
            if (i < t.length && t[i] == ']') { i++; return true }
            while (true) {
                espacos()
                if (!valor(p + 1)) return false
                espacos()
                if (i >= t.length) return false
                when (t[i++]) {
                    ',' -> continue
                    ']' -> return true
                    else -> return false
                }
            }
        }

        fun texto(): Boolean {
            i++
            while (i < t.length) {
                val c = t[i++]
                when {
                    c == '"' -> return true
                    c < ' ' -> return false
                    c == '\\' -> {
                        if (i >= t.length) return false
                        when (t[i++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> {
                                if (i + 4 > t.length || !t.substring(i, i + 4).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
                                i += 4
                            }
                            else -> return false
                        }
                    }
                }
            }
            return false
        }

        fun numero(): Boolean {
            if (i < t.length && t[i] == '-') i++
            if (i >= t.length) return false
            if (t[i] == '0') i++ else if (t[i] in '1'..'9') digitos() else return false
            if (i < t.length && t[i] == '.') {
                i++
                if (!digitos()) return false
            }
            if (i < t.length && (t[i] == 'e' || t[i] == 'E')) {
                i++
                if (i < t.length && (t[i] == '+' || t[i] == '-')) i++
                if (!digitos()) return false
            }
            return true
        }

        fun digitos(): Boolean {
            val ini = i
            while (i < t.length && t[i] in '0'..'9') i++
            return i > ini
        }
    }
}
