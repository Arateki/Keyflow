package com.keyflow.skins

import com.keyflow.i18n.tr

/** Pedaços de texto que o leitor e o store compartilham ao montar o [ImportReport]. */
internal object ReportText {
    /** "a.svg, b.svg, c.svg e mais 4": três nomes bastam para o autor achar o resto. */
    fun names(names: List<String>): String {
        val shown = names.take(3).joinToString(", ")
        return if (names.size <= 3) shown else tr("%s e mais %d", shown, names.size - 3)
    }
}
