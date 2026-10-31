package com.keyflow.computador.app

import java.io.ByteArrayOutputStream

/**
 * CMP-35, lado da **saída do emulador para o servidor**. O termlib devolve pelo mesmo `onKeyboardInput` as teclas do usuário e as respostas do
 * emulador, e a origem não se separa por flag (a saída nativa vai por `Handler.postAtTime`: medido na CMP-T1). Então o filtro olha **o que é**:
 * texto e teclas passam; entre as sequências de escape só passa o que está na lista fechada de teclas e de respostas permitidas
 * (DA1/DA2, DSR/CPR, tamanho, cores, kitty `a=q`). Todo o resto (DCS inteiro, OSC 52, relatório de título, DECRQSS, qualquer final de CSI
 * desconhecido) é descartado antes de chegar ao servidor.
 *
 * Sem estado: o termlib entrega cada tecla ou resposta num pedaço só. Uma sequência que **não termina** no pedaço é descartada
 * inteira (mas `ESC` sozinho, ou `ESC` + uma letra, é a tecla Esc/Alt e passa).
 */
class FiltroDeRespostas {
    /** Quantos pedaços de sequência foram descartados desde que o filtro nasceu (para os testes e o diagnóstico; nunca guarda conteúdo). */
    @Volatile var descartes: Int = 0
        private set

    fun filtrar(entrada: ByteArray): ByteArray {
        if (entrada.none { it.toInt() == ESC }) return entrada
        val saida = ByteArrayOutputStream(entrada.size)
        val n = entrada.size
        var i = 0
        while (i < n) {
            val b = entrada[i].toInt() and 0xff
            if (b != ESC) { saida.write(b); i++; continue }
            if (i + 1 >= n) { saida.write(ESC); i++; continue }
            when (entrada[i + 1].toInt() and 0xff) {
                '['.code -> {
                    if (i + 2 >= n) { saida.write(entrada, i, 2); i += 2; continue }
                    val fim = fimDeCsi(entrada, i + 2)
                    if (fim < 0) { descartes++; break }
                    if (csiPermitido(entrada, i + 2, fim)) saida.write(entrada, i, fim - i + 1) else descartes++
                    i = fim + 1
                }
                ']'.code -> {
                    if (i + 2 >= n) { saida.write(entrada, i, 2); i += 2; continue }
                    val fim = fimDeString(entrada, i + 2)
                    if (fim.fimDoConteudo < 0) { descartes++; break }
                    if (oscPermitido(entrada, i + 2, fim.fimDoConteudo)) saida.write(entrada, i, fim.proximo - i) else descartes++
                    i = fim.proximo
                }
                '_'.code -> {
                    if (i + 2 >= n) { saida.write(entrada, i, 2); i += 2; continue }
                    val fim = fimDeString(entrada, i + 2)
                    if (fim.fimDoConteudo < 0) { descartes++; break }
                    if (apcDoKittyPermitido(entrada, i + 2, fim.fimDoConteudo)) saida.write(entrada, i, fim.proximo - i) else descartes++
                    i = fim.proximo
                }
                'P'.code, 'X'.code, '^'.code -> {
                    // DCS, SOS e PM: o usuário nunca gera (Alt+P chega sozinho, tratado acima pelo tamanho do pedaço); o emulador não responde com eles.
                    if (i + 2 >= n) { saida.write(entrada, i, 2); i += 2; continue }
                    val fim = fimDeString(entrada, i + 2)
                    descartes++
                    if (fim.fimDoConteudo < 0) break
                    i = fim.proximo
                }
                'O'.code -> {
                    // SS3 (teclas em modo aplicação): ESC O <final>.
                    if (i + 2 < n && (entrada[i + 2].toInt() and 0xff) in 0x40..0x7e) { saida.write(entrada, i, 3); i += 3 } else { saida.write(ESC); i++ }
                }
                else -> { saida.write(ESC); i++ } // Alt+tecla: o próximo byte é tratado no laço (inclusive outro ESC).
            }
        }
        return saida.toByteArray()
    }

    private class FimDeString(val fimDoConteudo: Int, val proximo: Int)

    /** Índice do byte final do CSI (0x40..0x7e) depois de parâmetros e intermediários, ou -1 se não termina ou se tem byte inválido. */
    private fun fimDeCsi(d: ByteArray, de: Int): Int {
        var i = de
        while (i < d.size) {
            val b = d[i].toInt() and 0xff
            when {
                b in 0x20..0x3f -> i++
                b in 0x40..0x7e -> return i
                else -> return -1
            }
        }
        return -1
    }

    /** Termina em BEL ou `ESC \`. [FimDeString.fimDoConteudo] é onde o conteúdo acaba; [FimDeString.proximo], o primeiro byte depois do terminador. */
    private fun fimDeString(d: ByteArray, de: Int): FimDeString {
        var i = de
        while (i < d.size) {
            val b = d[i].toInt() and 0xff
            if (b == BEL) return FimDeString(i, i + 1)
            if (b == ESC && i + 1 < d.size && d[i + 1].toInt() == '\\'.code) return FimDeString(i, i + 2)
            i++
        }
        return FimDeString(-1, d.size)
    }

    private fun csiPermitido(d: ByteArray, de: Int, fim: Int): Boolean {
        val final = (d[fim].toInt() and 0xff).toChar()
        val corpo = String(d, de, fim - de, Charsets.ISO_8859_1)
        // Nenhum intermediário (espaço, `$`, `"`, `'`…): DECRQM, DECSCUSR e afins não estão na lista.
        if (corpo.any { it.code in 0x20..0x2f }) return false
        val prefixo = corpo.firstOrNull()?.takeIf { it in "<=>?" }
        val numeros = if (prefixo != null) corpo.substring(1) else corpo
        if (numeros.any { it !in '0'..'9' && it != ';' && it != ':' }) return false
        return when (final) {
            // Teclas: setas, Home/End, F1–F4, Shift+Tab, PageUp/Down e F5+ (`~`), kitty `u`, foco (I/O).
            'A', 'B', 'C', 'D', 'E', 'F', 'H', 'P', 'Q', 'S', 'Z', '~', 'u' -> prefixo == null || (final == 'u' && prefixo == '?')
            'I', 'O' -> prefixo == null && numeros.isEmpty()
            // CPR (`r;c R`) e F3 (`1;mod R`).
            'R' -> prefixo == null && numeros.isNotEmpty()
            // DA1 (`? … c`) e DA2 (`> … c`).
            'c' -> prefixo == '?' || prefixo == '>'
            // DSR: `0 n` ou `? … n`.
            'n' -> (prefixo == null && numeros == "0") || prefixo == '?'
            // Mouse SGR.
            'M', 'm' -> prefixo == '<' && numeros.isNotEmpty()
            // Tamanho: `4;h;w t` (pixels) e `8;linhas;colunas t` (células); `6;ch;cw t` (célula).
            't' -> prefixo == null && numeros.substringBefore(';') in setOf("4", "6", "8")
            else -> false
        }
    }

    private val cor = Regex("^(?:(?:10|11|12);|4;[0-9]{1,3};)rgb:[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}$")

    private fun oscPermitido(d: ByteArray, de: Int, fimConteudo: Int): Boolean {
        if (fimConteudo - de > 64) return false
        return cor.matches(String(d, de, fimConteudo - de, Charsets.ISO_8859_1))
    }

    /** Resposta do kitty `a=q`: `G` + chaves `k=v` separadas por vírgula + `;` + texto imprimível curto (`OK`, `ENOTSUP:…`). */
    private fun apcDoKittyPermitido(d: ByteArray, de: Int, fimConteudo: Int): Boolean {
        if (fimConteudo - de < 2 || fimConteudo - de > 300) return false
        val texto = String(d, de, fimConteudo - de, Charsets.ISO_8859_1)
        if (texto[0] != 'G') return false
        val ponto = texto.indexOf(';')
        if (ponto < 0) return false
        val chaves = texto.substring(1, ponto)
        val mensagem = texto.substring(ponto + 1)
        return chaves.all { it.isLetterOrDigit() || it == '=' || it == ',' } && mensagem.all { it.code in 0x20..0x7e }
    }

    private companion object {
        const val ESC = 0x1b
        const val BEL = 0x07
    }
}

/**
 * CMP-35, lado do **servidor para o emulador**: consome, antes do termlib, o que o app não quer que seja respondido ou exibido —
 * `ENQ`, `DECRQSS` (`DCS $ q … ST`), relatório de título (`CSI 20 t` e `CSI 21 t`), `OSC 21` e a **leitura** do clipboard (`OSC 52 ; … ; ?`).
 * A escrita (`OSC 52` com dados) passa: o termlib a entrega ao `onClipboardCopy`, onde o consentimento mora.
 *
 * Também lê o título (`OSC 0` e `OSC 2`) **sem alterá-lo**, para o rótulo da aba (o termlib 0.3.14 não publica o título), e o entrega a
 * [aoTitulo] já sanitizado por [RotuloDeAba].
 *
 * Com estado: sequências partidas entre pedaços (a rede parte onde quiser) são seguradas até a decisão. O resultado de qualquer divisão em
 * pedaços é igual ao do fluxo inteiro (`FiltroDaSaidaDoServidorTest` confere por sorteio).
 */
class FiltroDaSaidaDoServidor(private val aoTitulo: (String) -> Unit = {}) {
    private enum class E { NORMAL, ESC, CSI, CSI_DESCARTA, OSC, DCS, STRING_PASSA, STRING_PASSA_ESC, STRING_DESCARTA, STRING_DESCARTA_ESC, TITULO, TITULO_ESC }

    private var estado = E.NORMAL
    private val seguro = ByteArrayOutputStream()
    private val titulo = ByteArrayOutputStream()
    private var saida = ByteArrayOutputStream()
    private var descartados = 0
    @Volatile var consumidos: Int = 0
        private set

    /** DECCKM (`CSI ? 1 h` / `l`): o servidor ligou o modo de aplicação das setas? O termlib não publica; o mapa do teclado precisa. */
    @Volatile var modoAplicacaoDasSetas: Boolean = false
        private set

    /** Processa [entrada] e devolve o que segue para o emulador. Não é seguro chamar de duas threads ao mesmo tempo. */
    fun processar(entrada: ByteArray): ByteArray {
        saida = ByteArrayOutputStream(entrada.size)
        for (x in entrada) passo(x.toInt() and 0xff)
        return saida.toByteArray()
    }

    fun reiniciar() { modoAplicacaoDasSetas = false; estado = E.NORMAL; seguro.reset(); titulo.reset(); descartados = 0 }

    private fun emitirSegurados() { seguro.writeTo(saida); seguro.reset() }
    private fun passa(b: Int) { saida.write(b) }

    private fun iniciarEsc() { seguro.reset(); seguro.write(ESC); estado = E.ESC }

    private fun passo(b: Int) {
        when (estado) {
            E.NORMAL -> when (b) {
                ESC -> iniciarEsc()
                ENQ -> consumidos++
                else -> passa(b)
            }
            E.ESC -> when (b) {
                '['.code -> { seguro.write(b); estado = E.CSI }
                ']'.code -> { seguro.write(b); estado = E.OSC }
                'P'.code -> { seguro.write(b); estado = E.DCS }
                ESC -> passa(ESC) // o primeiro ESC passa; o segundo continua segurado
                else -> { emitirSegurados(); estado = E.NORMAL; passo(b) }
            }
            E.CSI -> when {
                b in 0x20..0x3f -> { seguro.write(b); if (seguro.size() > MAX_CSI) { seguro.reset(); estado = E.CSI_DESCARTA } }
                b in 0x40..0x7e -> {
                    val corpo = seguro.toByteArray().copyOfRange(2, seguro.size()).toString(Charsets.ISO_8859_1)
                    if (b == 't'.code && (corpo == "21" || corpo == "20")) { consumidos++; seguro.reset() } else {
                        if ((b == 'h'.code || b == 'l'.code) && corpo.startsWith("?") && "1" in corpo.substring(1).split(';')) modoAplicacaoDasSetas = b == 'h'.code
                        seguro.write(b); emitirSegurados()
                    }
                    estado = E.NORMAL
                }
                // Byte que não pertence a um CSI (controle, ESC, UTF-8): o pedaço segurado é **descartado**, não emitido. Emitir deixaria o emulador no
                // meio de um CSI enquanto o filtro volta ao normal, e uma remoção seguinte (ENQ, CSI 21 t) poderia juntar as duas pontas numa sequência proibida.
                else -> { seguro.reset(); estado = E.NORMAL; consumidos++; passo(b) }
            }
            E.CSI_DESCARTA -> when {
                b in 0x20..0x3f -> Unit
                b in 0x40..0x7e -> estado = E.NORMAL
                else -> { estado = E.NORMAL; passo(b) }
            }
            E.OSC -> {
                if (b == BEL) { emitirSegurados(); passa(b); estado = E.NORMAL; return }
                if (b == ESC) { seguro.reset(); consumidos++; iniciarEsc(); return }
                // Controle dentro do prefixo do OSC: malformado, engolido até o terminador (pelo mesmo motivo do CSI).
                if (b < 0x20) { seguro.reset(); descartados = 0; consumidos++; estado = E.STRING_DESCARTA; return }
                seguro.write(b)
                val corpo = seguro.toByteArray().copyOfRange(2, seguro.size()).toString(Charsets.ISO_8859_1)
                when (decidirOsc(corpo)) {
                    Decisao.AINDA_NAO -> if (seguro.size() > MAX_PREFIXO_OSC) { emitirSegurados(); estado = E.STRING_PASSA }
                    Decisao.PASSA -> { emitirSegurados(); estado = E.STRING_PASSA }
                    Decisao.DESCARTA -> { consumidos++; seguro.reset(); descartados = 0; estado = E.STRING_DESCARTA }
                    Decisao.TITULO -> { emitirSegurados(); titulo.reset(); estado = E.TITULO }
                }
            }
            E.DCS -> {
                // ESC dentro do prefixo aborta o DCS: o `ESC P` segurado some (emiti-lo deixaria o emulador numa string aberta e o que vem depois sairia como conteúdo).
                if (b == ESC) { seguro.reset(); consumidos++; iniciarEsc(); return }
                seguro.write(b)
                val corpo = seguro.toByteArray().copyOfRange(2, seguro.size()).toString(Charsets.ISO_8859_1)
                when {
                    corpo == "$" -> Unit
                    corpo == "\$q" -> { consumidos++; seguro.reset(); descartados = 0; estado = E.STRING_DESCARTA }
                    else -> { emitirSegurados(); estado = E.STRING_PASSA }
                }
            }
            E.STRING_PASSA -> if (b == ENQ) consumidos++ else if (b == ESC) { seguro.reset(); seguro.write(ESC); estado = E.STRING_PASSA_ESC } else { passa(b); if (b == BEL) estado = E.NORMAL }
            E.STRING_PASSA_ESC -> {
                if (b == '\\'.code) { emitirSegurados(); passa(b); estado = E.NORMAL } else { estado = E.ESC; passo(b) }
            }
            E.STRING_DESCARTA -> when {
                b == BEL -> estado = E.NORMAL
                b == ESC -> estado = E.STRING_DESCARTA_ESC
                else -> if (++descartados > MAX_STRING_DESCARTADA) estado = E.NORMAL
            }
            E.STRING_DESCARTA_ESC -> {
                if (b == '\\'.code) estado = E.NORMAL else { iniciarEsc(); passo(b) }
            }
            E.TITULO -> when (b) {
                BEL -> { entregarTitulo(); passa(b); estado = E.NORMAL }
                ESC -> { seguro.reset(); seguro.write(ESC); estado = E.TITULO_ESC }
                ENQ -> consumidos++
                else -> { passa(b); if (titulo.size() <= MAX_TITULO_BRUTO) titulo.write(b) }
            }
            E.TITULO_ESC -> {
                if (b == '\\'.code) { entregarTitulo(); emitirSegurados(); passa(b); estado = E.NORMAL } else { titulo.reset(); estado = E.ESC; passo(b) }
            }
        }
    }

    private enum class Decisao { AINDA_NAO, PASSA, DESCARTA, TITULO }

    private fun decidirOsc(corpo: String): Decisao {
        val ponto = corpo.indexOf(';')
        val numero = if (ponto < 0) corpo else corpo.substring(0, ponto)
        if (numero.isNotEmpty() && numero.any { it !in '0'..'9' }) return Decisao.PASSA
        if (ponto < 0) return if (numero.length > 4) Decisao.PASSA else Decisao.AINDA_NAO
        return when (numero) {
            "0", "2" -> Decisao.TITULO
            "21" -> Decisao.DESCARTA
            "52" -> {
                // `52 ; <seleção> ; <dados>`: só a leitura (`?`) é descartada.
                val resto = corpo.substring(ponto + 1)
                val p2 = resto.indexOf(';')
                if (p2 < 0) Decisao.AINDA_NAO
                else if (p2 + 1 >= resto.length) Decisao.AINDA_NAO
                else if (resto[p2 + 1] == '?') Decisao.DESCARTA else Decisao.PASSA
            }
            else -> Decisao.PASSA
        }
    }

    private fun entregarTitulo() {
        val bruto = titulo.toByteArray().toString(Charsets.UTF_8)
        // O corpo ainda traz o prefixo "0;" ou "2;" que já foi segurado; o que sobra em [titulo] é só o texto.
        RotuloDeAba.sanitizar(bruto)?.let(aoTitulo)
        titulo.reset()
    }

    private companion object {
        const val ESC = 0x1b
        const val BEL = 0x07
        const val ENQ = 0x05
        const val MAX_CSI = 48
        const val MAX_PREFIXO_OSC = 24
        const val MAX_STRING_DESCARTADA = 8192
        const val MAX_TITULO_BRUTO = 512
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
