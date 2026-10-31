package com.keyflow.computador.app

import java.io.ByteArrayOutputStream

/** CMP-35: lista fechada de teclas/respostas, inclusive C1 de oito bits. Cada callback traz uma resposta completa. */
class FiltroDeRespostas {
    @Volatile var descartes: Int = 0
        private set

    fun filtrar(entrada: ByteArray): ByteArray {
        val saida = ByteArrayOutputStream(entrada.size)
        val parser = ParserVt(
            texto = { saida.write(it) },
            descarte = { descartes++ },
            saidaDoEmulador = true,
            sequencia = { tipo, bruto, corpo ->
                val t = corpo.toString(Charsets.ISO_8859_1)
                val permitir = when (tipo) {
                    ParserVt.Tipo.ESC -> t.length == 1 || t == " F"
                    ParserVt.Tipo.CSI -> CsiVt.ler(t)?.let(::csiPermitido) == true
                    ParserVt.Tipo.OSC -> OscVt.ler(t)?.let { cor.matches("${it.comando};${it.texto}") } == true
                    ParserVt.Tipo.APC -> apcDoKittyPermitido(t)
                    else -> false
                }
                if (permitir) saida.write(bruto) else descartes++
            },
        )
        parser.processar(entrada)
        parser.terminarResposta()
        return saida.toByteArray()
    }

    private fun csiPermitido(c: CsiVt): Boolean {
        if (c.intermediarios.isNotEmpty()) return false
        val a = c.argumentos
        return when (c.final) {
            'A', 'B', 'C', 'D', 'E', 'F', 'H', 'P', 'Q', 'S', 'Z', '~', 'u' -> c.lider.isEmpty() || (c.final == 'u' && c.lider == "?")
            'I', 'O' -> c.lider.isEmpty() && a == listOf(null)
            'R' -> c.lider.isEmpty() && a.any { it != null }
            'c' -> c.lider == "?" || c.lider == ">"
            'n' -> (c.lider.isEmpty() && a == listOf(0)) || c.lider == "?"
            'M', 'm' -> c.lider == "<" && a.any { it != null }
            't' -> c.lider.isEmpty() && a.first() in setOf(4, 6, 8)
            else -> false
        }
    }

    private val cor = Regex("^(?:(?:10|11|12);|4;[0-9]{1,3};)rgb:[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}$")
    private fun apcDoKittyPermitido(t: String): Boolean {
        if (t.length !in 2..300 || t[0] != 'G') return false
        val ponto = t.indexOf(';')
        return ponto >= 0 && t.substring(1, ponto).all { it.isLetterOrDigit() || it == '=' || it == ',' } && t.substring(ponto+1).all { it.code in 0x20..0x7e }
    }
}

/**
 * CMP-35: o fluxo do servidor é tokenizado antes de decidir. Prefixos e strings só saem completos;
 * cancelamento descarta o trecho ainda retido. Assim retirar uma consulta nunca deixa um OSC/DCS aberto no libvterm.
 */
class FiltroDaSaidaDoServidor(private val aoTitulo: (String) -> Unit = {}) {
    private var saida = ByteArrayOutputStream()
    @Volatile var consumidos: Int = 0
        private set
    @Volatile var modoAplicacaoDasSetas: Boolean = false
        private set
    private val parser = ParserVt(texto = { saida.write(it) }, descarte = { consumidos++ }, sequencia = ::sequencia)

    fun processar(entrada: ByteArray): ByteArray {
        saida = ByteArrayOutputStream(entrada.size)
        parser.processar(entrada)
        return saida.toByteArray()
    }
    fun reiniciar() { modoAplicacaoDasSetas = false; parser.reiniciar() }

    private fun sequencia(tipo: ParserVt.Tipo, bruto: ByteArray, corpo: ByteArray) {
        val t = corpo.toString(Charsets.ISO_8859_1)
        when (tipo) {
            ParserVt.Tipo.ESC -> if (t == " G") { consumidos++; return }
            ParserVt.Tipo.CSI -> {
                val c = CsiVt.ler(t) ?: run { consumidos++; return }
                if (c.lider.isEmpty() && c.intermediarios.isEmpty() && c.final == 't' && c.argumentos.first() in setOf(20, 21)) { consumidos++; return }
                if (c.lider == "?" && c.intermediarios.isEmpty() && c.final in "hl" && 1 in c.argumentos) modoAplicacaoDasSetas = c.final == 'h'
            }
            ParserVt.Tipo.OSC -> {
                val osc = OscVt.ler(t) ?: run { consumidos++; return }
                if (osc.comando == 21 || (osc.comando == 52 && osc.texto.substringAfter(';', "").startsWith("?"))) { consumidos++; return }
                if (osc.comando == 0 || osc.comando == 2) RotuloDeAba.sanitizar(osc.texto.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8))?.let(aoTitulo)
            }
            ParserVt.Tipo.DCS -> if (t.startsWith("\$q")) { consumidos++; return }
            else -> Unit
        }
        saida.write(bruto)
    }
}

/** O texto que vira rótulo da aba (CMP-35): sem controles, sem marcas de direção, até 40 caracteres. */
object RotuloDeAba {
    const val MAX = 40

    /** `null` quando não sobra nada. */
    fun sanitizar(bruto: String): String? {
        val limpo = StringBuilder()
        var ultimoEspaco = true
        var i = 0
        var pontos = 0
        while (i < bruto.length && pontos < MAX) {
            val cp = bruto.codePointAt(i)
            i += Character.charCount(cp)
            val tipo = Character.getType(cp)
            val controle = tipo == Character.CONTROL.toInt() || tipo == Character.FORMAT.toInt() || tipo == Character.LINE_SEPARATOR.toInt() ||
                tipo == Character.PARAGRAPH_SEPARATOR.toInt() || tipo == Character.PRIVATE_USE.toInt() || tipo == Character.UNASSIGNED.toInt() || tipo == Character.SURROGATE.toInt()
            if (controle) continue
            if (Character.isWhitespace(cp) || cp == 0xa0) {
                if (!ultimoEspaco) { limpo.append(' '); pontos++ }
                ultimoEspaco = true
                continue
            }
            limpo.appendCodePoint(cp)
            pontos++
            ultimoEspaco = false
        }
        return limpo.toString().trim().ifEmpty { null }
    }
}

/**
 * CMP-28: o que sai de uma colagem (ou de um ditado com quebra de linha). O termlib não publica o estado do bracketed paste, então a
 * confirmação vale sempre que há quebra de linha. O texto perde `ESC [ 2 0 0 ~` e `ESC [ 2 0 1 ~` **até não sobrar nenhum** (a remoção em uma passada
 * deixaria `ESC ESC [ 2 0 1 ~ [ 2 0 1 ~` virar a marca de novo) e depois todo `ESC` e controle (menos tab, `\n` e `\r`) e os controles C1.
 */
object PoliticaDeColar {
    class Preparado(val texto: String, val linhas: Int) {
        val precisaConfirmar: Boolean get() = linhas > 1 || texto.any { it == '\n' || it == '\r' }
    }

    fun preparar(original: String): Preparado {
        var t = original
        while (true) {
            val novo = t.replace("\u001b[201~", "").replace("\u001b[200~", "")
            if (novo == t) break
            t = novo
        }
        val limpo = buildString(t.length) {
            for (c in t) {
                val manter = c == '\t' || c == '\n' || c == '\r' || (c.code >= 0x20 && c.code != 0x7f && c.code !in 0x80..0x9f)
                if (manter) append(c)
            }
        }
        return Preparado(limpo, contarLinhas(limpo))
    }

    private fun contarLinhas(t: String): Int {
        if (t.isEmpty()) return 0
        var n = 1
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\r' && i + 1 < t.length && t[i + 1] == '\n') { n++; i += 2; continue }
            if (c == '\n' || c == '\r') n++
            i++
        }
        // Uma quebra no fim não abre linha nova com texto, mas **executa** a anterior: continua contando como linha para a confirmação.
        return n
    }
}

/** Links `OSC 8` (CMP-25, CMP-35): só `http`, `https` e `mailto`; qualquer outro esquema nunca vira `Intent`. */
object PoliticaDeLinks {
    private const val MAX = 2048

    /** A URL **inteira** a mostrar no diálogo, ou `null` se o link não pode ser aberto. */
    fun aceitar(url: String): String? {
        val u = url.trim()
        if (u.isEmpty() || u.length > MAX) return null
        if (u.any { it.code < 0x20 || it.code == 0x7f || it.code in 0x80..0x9f || Character.isWhitespace(it) || Character.getType(it) == Character.FORMAT.toInt() }) return null
        val doisPontos = u.indexOf(':')
        if (doisPontos <= 0) return null
        val esquema = u.substring(0, doisPontos)
        if (esquema.any { !(it.isLetterOrDigit() || it == '+' || it == '-' || it == '.') }) return null
        return when (esquema.lowercase()) {
            "http", "https" -> {
                val resto = u.substring(doisPontos + 1)
                if (!resto.startsWith("//")) return null
                val autoridade = resto.removePrefix("//").takeWhile { it != '/' && it != '?' && it != '#' }
                // `usuario@host`: o trecho antes do `@` engana quem lê o domínio; recusado.
                if (autoridade.isEmpty() || '@' in autoridade || autoridade.startsWith(":")) null else u
            }
            "mailto" -> if (u.length > doisPontos + 1) u else null
            else -> null
        }
    }
}
