package com.keyflow.computador.app

/** A gravação "hostil" (CMP-35): um trecho por ataque ou consulta. O arquivo `hostil.bin` é a concatenação, e o teste confere. */
object Hostil {
    const val ESC = "\u001b"
    const val BEL = "\u0007"
    const val ST = "\u001b\\"

    val segmentos: List<Pair<String, String>> = listOf(
        "texto" to "Olá, terminal!\r\n",
        "da1" to "$ESC[c",
        "da2" to "$ESC[>c",
        "dsr" to "$ESC[5n",
        "cpr" to "$ESC[6n",
        "cor10" to "$ESC]10;?$BEL",
        "cor11" to "$ESC]11;?$BEL",
        "kitty-q" to "${ESC}_Ga=q,t=d,f=24,s=1,v=1,i=50;AAAA$ST",
        "osc52-leitura" to "$ESC]52;c;?$BEL",
        "osc52-leitura-st" to "$ESC]52;;?$ST",
        "osc52-escrita" to "$ESC]52;c;aGVsbG8=$BEL",
        "relatorio-titulo-21" to "$ESC[21t",
        "relatorio-titulo-20" to "$ESC[20t",
        "osc21" to "$ESC]21;?$ST",
        "decrqss" to "${ESC}P\$qm$ST",
        "enq" to "\u0005",
        "titulo" to "$ESC]0;build-box: ~/app$BEL",
        "link-javascript" to "$ESC]8;;javascript:alert(1)${ST}clique$ESC]8;;$ST",
        "link-file" to "$ESC]8;;file:///etc/passwd${ST}arquivo$ESC]8;;$ST",
        "link-https" to "$ESC]8;;https://exemplo.org/a${ST}site$ESC]8;;$ST",
        "final" to "fim\r\n",
    )

    fun trecho(nome: String): String = segmentos.first { it.first == nome }.second
    fun tudo(): ByteArray = segmentos.joinToString("") { it.second }.toByteArray(Charsets.UTF_8)
    fun bytes(nome: String): ByteArray = trecho(nome).toByteArray(Charsets.UTF_8)
}

/** Procura uma sequência de bytes dentro de outra (os testes comparam bytes, não texto). */
fun ByteArray.contem(trecho: ByteArray): Boolean {
    if (trecho.isEmpty()) return true
    outer@ for (i in 0..size - trecho.size) {
        for (j in trecho.indices) if (this[i + j] != trecho[j]) continue@outer
        return true
    }
    return false
}

fun ByteArray.contem(texto: String) = contem(texto.toByteArray(Charsets.ISO_8859_1))
