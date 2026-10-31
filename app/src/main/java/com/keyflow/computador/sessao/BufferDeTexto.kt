package com.keyflow.computador.sessao

/**
 * Anel de linhas de **texto puro** alimentado pelo mesmo fluxo de bytes que o emulador (CMP-36): é o que o agente lê e a busca usa.
 * Decodifica UTF-8 em fluxo (sequências partidas entre pacotes), descarta CSI/OSC/APC/DCS/PM/SOS e escapes curtos, aplica `\r`, `\b` e os
 * apagamentos de linha mais simples (`CSI K`, `CSI 2K`). **Não** é um emulador: a fonte da verdade visual é o termlib. Só memória:
 * este arquivo não tem nenhuma API de arquivo, e um teste confere isso.
 */
class BufferDeTexto(private val maxLinhas: Int = MAX_LINHAS, private val maxColunas: Int = MAX_COLUNAS) {
    private val linhas = ArrayDeque<String>()
    private val atual = StringBuilder()
    private var coluna = 0
    private var estado = Estado.TEXTO
    private val pendente = java.io.ByteArrayOutputStream()
    private var parametros = StringBuilder()

    private enum class Estado { TEXTO, ESC, CSI, OSC, OSC_ESC, STRING, STRING_ESC, ESC_INTERMEDIARIO }

    /** Alimenta com bytes da saída do servidor. */
    @Synchronized
    fun alimentar(bytes: ByteArray) {
        pendente.write(bytes)
        val todos = pendente.toByteArray()
        pendente.reset()
        var i = 0
        while (i < todos.size) {
            val b = todos[i].toInt() and 0xff
            if (estado == Estado.TEXTO && b >= 0x80) {
                val tam = comprimentoUtf8(b)
                if (tam == 0) { escrever('�'); i++; continue }
                if (i + tam > todos.size) { pendente.write(todos, i, todos.size - i); return }
                String(todos, i, tam, Charsets.UTF_8).forEach { escrever(it) }
                i += tam
                continue
            }
            processar(b)
            i++
        }
    }

    /** Escreve texto **local** (não vem do servidor), como o separador de reconexão. */
    @Synchronized
    fun adicionarLocal(texto: String) = alimentar(texto.toByteArray(Charsets.UTF_8))

    /** As últimas [n] linhas, a linha em edição incluída (se não estiver vazia). */
    @Synchronized
    fun ultimasLinhas(n: Int): List<String> {
        val todas = if (atual.isNotEmpty()) linhas + atual.toString() else linhas.toList()
        return todas.takeLast(n.coerceAtLeast(0))
    }

    @Synchronized
    fun texto(): String = ultimasLinhas(Int.MAX_VALUE).joinToString("\n")

    @Synchronized
    fun quantidadeDeLinhas(): Int = linhas.size + if (atual.isNotEmpty()) 1 else 0

    @Synchronized
    fun limpar() { linhas.clear(); atual.setLength(0); coluna = 0; estado = Estado.TEXTO; pendente.reset() }

    private fun comprimentoUtf8(b: Int) = when {
        b in 0xC2..0xDF -> 2
        b in 0xE0..0xEF -> 3
        b in 0xF0..0xF4 -> 4
        else -> 0
    }

    private fun processar(b: Int) {
        when (estado) {
            Estado.TEXTO -> when (b) {
                0x1b -> estado = Estado.ESC
                '\n'.code -> novaLinha()
                '\r'.code -> coluna = 0
                0x08 -> if (coluna > 0) coluna--
                '\t'.code -> repeat(8 - coluna % 8) { escrever(' ') }
                in 0x00..0x1f, 0x7f -> Unit
                else -> escrever(b.toChar())
            }
            Estado.ESC -> when (b.toChar()) {
                '[' -> { estado = Estado.CSI; parametros = StringBuilder() }
                ']' -> estado = Estado.OSC
                'P', '_', '^', 'X' -> estado = Estado.STRING
                in ' '..'/' -> estado = Estado.ESC_INTERMEDIARIO
                else -> estado = Estado.TEXTO
            }
            Estado.ESC_INTERMEDIARIO -> if (b !in 0x20..0x2f) estado = Estado.TEXTO
            Estado.CSI -> if (b in 0x40..0x7e) { fimDeCsi(b.toChar()); estado = Estado.TEXTO } else parametros.append(b.toChar())
            Estado.OSC -> when (b) { 0x07 -> estado = Estado.TEXTO; 0x1b -> estado = Estado.OSC_ESC }
            Estado.OSC_ESC -> estado = if (b == '\\'.code) Estado.TEXTO else Estado.OSC
            Estado.STRING -> if (b == 0x1b) estado = Estado.STRING_ESC
            Estado.STRING_ESC -> estado = if (b == '\\'.code) Estado.TEXTO else Estado.STRING
        }
    }

    private fun fimDeCsi(final: Char) {
        if (final != 'K') return
        when (parametros.toString().ifEmpty { "0" }) {
            "0" -> if (coluna < atual.length) atual.setLength(coluna)
            "1" -> for (i in 0 until minOf(coluna, atual.length)) atual.setCharAt(i, ' ')
            "2" -> { atual.setLength(0); coluna = 0 }
        }
    }

    private fun escrever(c: Char) {
        if (coluna >= maxColunas) return
        while (atual.length < coluna) atual.append(' ')
        if (coluna < atual.length) atual.setCharAt(coluna, c) else atual.append(c)
        coluna++
    }

    private fun novaLinha() {
        linhas.addLast(atual.toString().trimEnd())
        atual.setLength(0)
        coluna = 0
        while (linhas.size > maxLinhas) linhas.removeFirst()
    }

    companion object {
        const val MAX_LINHAS = 5_000
        const val MAX_COLUNAS = 4_096
        const val SEPARADOR_DE_RECONEXAO = "\r\n— conexão reiniciada —\r\n"
    }
}
