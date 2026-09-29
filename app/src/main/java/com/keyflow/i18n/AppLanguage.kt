package com.keyflow.i18n

import java.util.Locale

/**
 * Idiomas da interface do KeyFlow (Yan, 2026-09-28). O português é a língua de origem: os textos moram no
 * código em português e [tr] traduz para os outros. [short] é o que o botão do cabeçalho mostra; [nativeName],
 * o nome no próprio idioma (como no seletor de idioma do teclado).
 */
enum class AppLanguage(val code: String, val short: String, val nativeName: String) {
    PT("pt", "PT", "Português"),
    EN("en", "EN", "English"),
    ES("es", "ES", "Español"),
    ;

    companion object {
        /** Idioma salvo nas configurações; vazio (ainda não escolhido) ou desconhecido = português, a origem. */
        fun of(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: PT

        /** Primeira abertura: o idioma do aparelho, se for um dos três; senão, inglês. */
        fun fromSystem(locale: Locale = Locale.getDefault()): AppLanguage =
            entries.firstOrNull { it.code == locale.language } ?: EN
    }
}
