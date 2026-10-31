package com.keyflow.computador.app

import java.io.ByteArrayOutputStream

/**
 * Fronteira de sequências conforme o parser.c fixado no fork: C0/DEL não encerram comandos, CAN/SUB cancelam,
 * ESC reinicia comandos e, dentro de strings, só ESC \\ vira C1. Nenhum prefixo é entregue antes da decisão.
 * O orçamento descarta a sequência inteira até o cancelamento/final; nunca muda para "passar" por excesso.
 */
internal class ParserVt(
    private val texto: (Int) -> Unit,
    private val sequencia: (Tipo, ByteArray, ByteArray) -> Unit,
    private val descarte: () -> Unit,
    private val saidaDoEmulador: Boolean = false,
) {
    enum class Tipo { ESC, CSI, OSC, DCS, APC, PM, SOS }
    private enum class Estado { NORMAL, ESC, CSI_LIDER, CSI_ARGS, CSI_INTERMED, OSC_COMANDO, DCS_COMANDO, STRING }
    private var estado = Estado.NORMAL
    private var tipo = Tipo.ESC
    private val bruto = ByteArrayOutputStream()
    private val semControles = ByteArrayOutputStream()
    private var excedeu = false
    private var escNaString = false
    private var utf8 = true
    private var continuacoes = 0

    fun reiniciar() { limpar(); utf8 = true; continuacoes = 0 }

    private fun limpar() { estado = Estado.NORMAL; bruto.reset(); semControles.reset(); excedeu = false; escNaString = false }
    private fun guardar(b: Int, semantico: Boolean = true) {
        if (excedeu) return
        if (bruto.size() >= MAX_SEQUENCIA) { bruto.reset(); semControles.reset(); excedeu = true; return }
        bruto.write(b)
        if (semantico) semControles.write(b)
    }
    private fun iniciar(t: Tipo, bytes: IntArray) {
        limpar(); tipo = t
        estado = when (t) {
            Tipo.ESC -> Estado.ESC
            Tipo.CSI -> Estado.CSI_LIDER
            Tipo.OSC -> Estado.OSC_COMANDO
            Tipo.DCS -> Estado.DCS_COMANDO
            else -> Estado.STRING
        }
        for (b in bytes) guardar(b, false)
    }
    private fun concluir() {
        if (excedeu) descarte() else {
            val conteudo = semControles.toByteArray()
            if (tipo == Tipo.ESC) {
                if (conteudo.contentEquals(byteArrayOf('%'.code.toByte(), '@'.code.toByte()))) utf8 = false
                if (conteudo.contentEquals(byteArrayOf('%'.code.toByte(), 'G'.code.toByte()))) utf8 = true
            }
            sequencia(tipo, bruto.toByteArray(), conteudo)
        }
        limpar()
    }

    /** O callback de saída entrega teclas/respostas completas; ESC sozinho e Alt+introduzidor continuam sendo teclas. */
    fun terminarResposta() {
        if (estado == Estado.NORMAL) return
        val b = bruto.toByteArray()
        if (!excedeu && b.size in 1..2 && b[0].toInt() == ESC) b.forEach { texto(it.toInt() and 255) } else descarte()
        limpar()
    }

    fun processar(bytes: ByteArray) { for (b in bytes) passo(b.toInt() and 255) }

    private fun emString() = estado == Estado.OSC_COMANDO || estado == Estado.DCS_COMANDO || estado == Estado.STRING
    private fun comum(b: Int) { if (estado == Estado.NORMAL) texto(b) else guardar(b) }

    private fun passo(b: Int) {
        // Um byte de continuação UTF-8 não é um controle C1 (por exemplo, ћ contém 0x9b).
        if (!escNaString && utf8 && continuacoes > 0 && b in 0x80..0xbf) { continuacoes--; comum(b); return }
        continuacoes = 0
        if (!escNaString && utf8 && (estado == Estado.NORMAL || emString()) && b in 0xc2..0xf4) {
            continuacoes = when { b < 0xe0 -> 1; b < 0xf0 -> 2; else -> 3 }
            comum(b); return
        }
        if (b == CAN || b == SUB) {
            if (saidaDoEmulador && estado == Estado.ESC && bruto.size() == 1) texto(ESC)
            else if (estado != Estado.NORMAL) descarte()
            limpar(); texto(b); return
        }
        if (b == ESC) {
            if (emString()) { guardar(b, false); escNaString = true } else {
                // Alt+Esc é uma tecla legítima; os prefixos de protocolos continuam retidos/descartados.
                if (saidaDoEmulador && estado == Estado.ESC && bruto.size() == 1) texto(ESC)
                else if (estado != Estado.NORMAL) descarte()
                iniciar(Tipo.ESC, intArrayOf(ESC))
            }
            return
        }
        if (b == 0 || b == 127 || (b < 32 && !(b == BEL && emString()))) {
            if (b == ENQ && !saidaDoEmulador) descarte() else if (estado == Estado.NORMAL) texto(b) else guardar(b, false)
            return
        }
        if (escNaString) {
            if (b == '\\'.code) { guardar(b, false); concluir(); return }
            // No libvterm, ESC [ dentro de uma string CANCELA a string e vira escape '[', não um CSI.
            // O prefixo não foi emitido: consumir esse escape evita inventar um CSI no emulador agora em NORMAL.
            descarte(); limpar()
            if (b in 0x40..0x5f) return
            iniciar(Tipo.ESC, intArrayOf(ESC))
        }
        if (saidaDoEmulador && estado == Estado.ESC && bruto.size() == 1 && b in 0xc2..0xf4) {
            // Alt+caractere Unicode não é uma resposta ANSI; mantém também continuações que parecem C1.
            bruto.toByteArray().forEach { texto(it.toInt() and 255) }
            limpar(); passo(b); return
        }
        when (estado) {
            Estado.NORMAL -> {
                val t = if (!utf8 || saidaDoEmulador) when (b) {
                    0x90 -> Tipo.DCS; 0x98 -> Tipo.SOS; 0x9b -> Tipo.CSI
                    0x9d -> Tipo.OSC; 0x9e -> Tipo.PM; 0x9f -> Tipo.APC
                    else -> null
                } else null
                if (t != null) iniciar(t, intArrayOf(b)) else if (saidaDoEmulador && b in 0x80..0x9f) descarte() else texto(b)
            }
            Estado.ESC -> {
                val t = if (semControles.size() == 0) when (b) {
                    '['.code -> Tipo.CSI; ']'.code -> Tipo.OSC; 'P'.code -> Tipo.DCS
                    '_'.code -> Tipo.APC; '^'.code -> Tipo.PM; 'X'.code -> Tipo.SOS
                    else -> null
                } else null
                if (t != null) {
                    // C0 entre ESC e o introduzidor foi segurado e precisa conservar a ordem.
                    val prefixo = bruto.toByteArray()
                    val jaExcedeu = excedeu
                    iniciar(t, intArrayOf())
                    excedeu = jaExcedeu
                    prefixo.forEach { guardar(it.toInt() and 255, false) }
                    guardar(b, false)
                } else {
                    // Bytes fora de intermediários/final são ignorados pelo libvterm, mantendo in_esc.
                    guardar(b, semantico = b in 0x20..0x7e)
                    if (b in 0x30..0x7e) concluir()
                }
            }
            Estado.CSI_LIDER -> if (b in 0x3c..0x3f) guardar(b) else { estado = Estado.CSI_ARGS; csi(b) }
            Estado.CSI_ARGS, Estado.CSI_INTERMED -> csi(b)
            Estado.OSC_COMANDO -> when {
                b in '0'.code..'9'.code -> guardar(b)
                b == ';'.code -> { guardar(b); estado = Estado.STRING }
                else -> { estado = Estado.STRING; string(b) }
            }
            Estado.DCS_COMANDO -> { guardar(b); if (b in 0x40..0x7e) estado = Estado.STRING }
            Estado.STRING -> string(b)
        }
    }
    private fun csi(b: Int) {
        if (estado == Estado.CSI_ARGS && (b in '0'.code..'9'.code || b == ';'.code || b == ':'.code)) { guardar(b); return }
        estado = Estado.CSI_INTERMED
        when (b) {
            in 0x20..0x2f -> guardar(b)
            in 0x40..0x7e -> { guardar(b); concluir() }
            else -> { descarte(); limpar() }
        }
    }
    private fun string(b: Int) {
        if (b == BEL || (b == 0x9c && (!utf8 || saidaDoEmulador))) { guardar(b, false); concluir() } else guardar(b)
    }
    private companion object {
        const val ESC = 27; const val CAN = 24; const val SUB = 26; const val BEL = 7; const val ENQ = 5
        const val MAX_SEQUENCIA = 1_048_576
    }
}

/** Valores fora da faixa do parser são recusados; não podem dar a volta e virar um comando conhecido. */
internal fun decimalVt(texto: String): Int? {
    val n = texto.trimStart('0').ifEmpty { "0" }
    return n.toIntOrNull()?.takeIf { it >= 0 && it < Int.MAX_VALUE }
}

internal data class CsiVt(val lider: String, val argumentos: List<Int?>, val intermediarios: String, val final: Char) {
    companion object {
        private val formato = Regex("^([<=>?]*)([0-9;:]*)([ -/]*)([@-~])$")
        fun ler(conteudo: String): CsiVt? {
            val m = formato.matchEntire(conteudo) ?: return null
            if (m.groupValues[1].length > 1 || m.groupValues[3].length > 15) return null
            val partes = m.groupValues[2].split(';', ':')
            if (partes.size > 16) return null
            val args = partes.map { if (it.isEmpty()) null else decimalVt(it) ?: return null }
            return CsiVt(m.groupValues[1], args, m.groupValues[3], m.groupValues[4][0])
        }
    }
}

internal data class OscVt(val comando: Int, val texto: String) {
    companion object {
        fun ler(conteudo: String): OscVt? {
            val n = conteudo.takeWhile { it in '0'..'9' }
            val comando = if (n.isEmpty()) -1 else decimalVt(n) ?: return null
            return OscVt(comando, conteudo.substring(n.length).removePrefix(";"))
        }
    }
}
