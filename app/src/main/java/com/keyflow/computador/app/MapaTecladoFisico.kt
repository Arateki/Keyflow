package com.keyflow.computador.app

/** As teclas que o app mapeia sozinho (as outras seguem para o termlib: letras e símbolos sem Ctrl/Alt, com a composição do teclado). */
sealed interface Tecla {
    /** Uma tecla de caractere (`a`..`z` e símbolos), em minúscula. */
    data class Caractere(val c: Char) : Tecla
    data object Enter : Tecla
    data object Tab : Tecla
    data object Esc : Tecla
    data object Backspace : Tecla
    data object Delete : Tecla
    data object Insert : Tecla
    data object Cima : Tecla
    data object Baixo : Tecla
    data object Esquerda : Tecla
    data object Direita : Tecla
    data object Home : Tecla
    data object End : Tecla
    data object PageUp : Tecla
    data object PageDown : Tecla
    data class F(val n: Int) : Tecla { init { require(n in 1..12) } }
}

data class Modificadores(val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) {
    val algum: Boolean get() = ctrl || alt || shift

    /** O número do xterm: 1 + shift(1) + alt(2) + ctrl(4). */
    val codigoXterm: Int get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    operator fun plus(o: Modificadores) = Modificadores(ctrl || o.ctrl, alt || o.alt, shift || o.shift)

    companion object { val Nenhum = Modificadores() }
}

/**
 * O que cada tecla vira em bytes (CMP-26), com as sequências do xterm. **Setas e Home/End** dependem do modo de aplicação (DECCKM,
 * `CSI ? 1 h`): `ESC O A` em vez de `ESC [ A`. Com modificador a forma é sempre `ESC [ 1 ; m A` (m = [Modificadores.codigoXterm]).
 * Devolve `null` para o que o app **não** mapeia (letra sem Ctrl/Alt, símbolo sem Ctrl/Alt): quem trata é o termlib, que cuida da
 * composição do teclado.
 */
object MapaTecladoFisico {
    private const val ESC = 0x1b.toByte()

    fun bytes(tecla: Tecla, m: Modificadores = Modificadores.Nenhum, modoAplicacao: Boolean = false): ByteArray? = when (tecla) {
        is Tecla.Caractere -> caractere(tecla.c, m)
        Tecla.Enter -> comAlt(m, byteArrayOf(0x0d))
        Tecla.Tab -> if (m.shift) csi("Z") else comAlt(m, byteArrayOf(0x09))
        Tecla.Esc -> byteArrayOf(ESC)
        Tecla.Backspace -> comAlt(m, byteArrayOf(if (m.ctrl) 0x08 else 0x7f))
        Tecla.Delete -> tilde(3, m)
        Tecla.Insert -> tilde(2, m)
        Tecla.Cima -> cursor('A', m, modoAplicacao)
        Tecla.Baixo -> cursor('B', m, modoAplicacao)
        Tecla.Direita -> cursor('C', m, modoAplicacao)
        Tecla.Esquerda -> cursor('D', m, modoAplicacao)
        Tecla.Home -> cursor('H', m, modoAplicacao)
        Tecla.End -> cursor('F', m, modoAplicacao)
        Tecla.PageUp -> tilde(5, m)
        Tecla.PageDown -> tilde(6, m)
        is Tecla.F -> funcao(tecla.n, m)
    }

    private fun caractere(c: Char, m: Modificadores): ByteArray? {
        if (!m.ctrl && !m.alt) return null
        val base: Int = if (m.ctrl) {
            when (c.lowercaseChar()) {
                in 'a'..'z' -> c.lowercaseChar().code - 'a'.code + 1
                ' ', '2', '@' -> 0
                '[' -> 0x1b
                '\\' -> 0x1c
                ']' -> 0x1d
                '^', '6' -> 0x1e
                '_', '/', '-' -> 0x1f
                else -> return null
            }
        } else (if (m.shift) c.uppercaseChar() else c).code
        val bytes = if (base < 0x80) byteArrayOf(base.toByte()) else String(Character.toChars(base)).toByteArray(Charsets.UTF_8)
        return if (m.alt) byteArrayOf(ESC) + bytes else bytes
    }

    private fun comAlt(m: Modificadores, b: ByteArray) = if (m.alt) byteArrayOf(ESC) + b else b

    private fun csi(s: String) = byteArrayOf(ESC, '['.code.toByte()) + s.toByteArray(Charsets.ISO_8859_1)

    private fun cursor(final: Char, m: Modificadores, aplicacao: Boolean): ByteArray = when {
        m.algum -> csi("1;${m.codigoXterm}$final")
        aplicacao -> byteArrayOf(ESC, 'O'.code.toByte(), final.code.toByte())
        else -> csi(final.toString())
    }

    private fun tilde(n: Int, m: Modificadores): ByteArray = if (m.algum) csi("$n;${m.codigoXterm}~") else csi("$n~")

    private val codigosF = mapOf(5 to 15, 6 to 17, 7 to 18, 8 to 19, 9 to 20, 10 to 21, 11 to 23, 12 to 24)

    private fun funcao(n: Int, m: Modificadores): ByteArray = if (n <= 4) {
        val letra = "PQRS"[n - 1]
        if (m.algum) csi("1;${m.codigoXterm}$letra") else byteArrayOf(ESC, 'O'.code.toByte(), letra.code.toByte())
    } else tilde(codigosF.getValue(n), m)
}

/**
 * O `aa` da T1: o mesmo `KeyEvent` chegando por dois caminhos (InputConnection e a view). Dois eventos **distintos** (duas teclas iguais
 * seguidas) nunca têm o mesmo `downTime`/`eventTime`; o repetir da tecla segurada muda `repeatCount` e `eventTime`.
 */
class DeduplicadorDeTeclas(private val memoria: Int = 8) {
    private data class Chave(val acao: Int, val codigo: Int, val downTime: Long, val eventTime: Long, val repeticao: Int)

    private val vistos = ArrayDeque<Chave>()

    /** `true` se este evento já foi visto (e deve ser ignorado). Registra o que for novo. */
    @Synchronized fun duplicada(acao: Int, codigo: Int, downTime: Long, eventTime: Long, repeticao: Int): Boolean {
        val c = Chave(acao, codigo, downTime, eventTime, repeticao)
        if (c in vistos) return true
        vistos.addLast(c)
        while (vistos.size > memoria) vistos.removeFirst()
        return false
    }
}

enum class EstadoPegajoso { DESLIGADO, UMA_VEZ, TRAVADO }

/**
 * Ctrl e Alt das teclas extras: um toque vale para a **próxima** tecla; outro toque trava; outro solta. Depois de uma tecla, o "uma vez" se
 * desfaz e o travado fica.
 */
class ModificadoresPegajosos {
    private var ctrl = EstadoPegajoso.DESLIGADO
    private var alt = EstadoPegajoso.DESLIGADO

    @Synchronized fun estadoCtrl() = ctrl
    @Synchronized fun estadoAlt() = alt

    @Synchronized fun tocarCtrl() { ctrl = proximo(ctrl) }
    @Synchronized fun tocarAlt() { alt = proximo(alt) }

    /** O que vale agora. */
    @Synchronized fun atuais() = Modificadores(ctrl = ctrl != EstadoPegajoso.DESLIGADO, alt = alt != EstadoPegajoso.DESLIGADO)

    /** Depois de usar uma tecla: o "uma vez" some. */
    @Synchronized fun consumir() {
        if (ctrl == EstadoPegajoso.UMA_VEZ) ctrl = EstadoPegajoso.DESLIGADO
        if (alt == EstadoPegajoso.UMA_VEZ) alt = EstadoPegajoso.DESLIGADO
    }

    private fun proximo(e: EstadoPegajoso) = when (e) {
        EstadoPegajoso.DESLIGADO -> EstadoPegajoso.UMA_VEZ
        EstadoPegajoso.UMA_VEZ -> EstadoPegajoso.TRAVADO
        EstadoPegajoso.TRAVADO -> EstadoPegajoso.DESLIGADO
    }
}

/**
 * CMP-27/28: texto com **quebra de linha** que chega ao `onKeyboardInput` junto de outros caracteres (ditado da orbe, "colar" do teclado,
 * texto sugerido) não vai direto ao servidor: uma quebra executa o comando. O **Enter** do usuário chega sozinho (`\r`), e o que o app mesmo
 * colou (já confirmado) passa durante [janelaMs]. Sequências de escape nunca são seguradas.
 */
class GuardaDeLinhas(private val janelaMs: Long = 3_000) {
    sealed interface Decisao {
        data object Enviar : Decisao
        class Segurar(val texto: String) : Decisao
    }

    @Volatile private var liberadoAte = 0L

    /** O app vai colar algo já confirmado: o que sair do emulador nos próximos [janelaMs] passa. */
    fun liberar(agoraMs: Long) { liberadoAte = agoraMs + janelaMs }

    fun avaliar(bytes: ByteArray, agoraMs: Long): Decisao {
        if (agoraMs < liberadoAte) return Decisao.Enviar
        if (bytes.size < 2 || bytes[0].toInt() == 0x1b) return Decisao.Enviar
        if (bytes.none { it.toInt() == 0x0a || it.toInt() == 0x0d }) return Decisao.Enviar
        val texto = String(bytes, Charsets.UTF_8)
        // Várias teclas de controle juntas (não é texto): não seguro.
        if (texto.all { it == '\r' || it == '\n' }) return Decisao.Enviar
        return Decisao.Segurar(texto)
    }
}
