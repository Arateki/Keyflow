package com.keyflow.i18n

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.After
import org.junit.Test

/** O idioma da interface e a tradução por texto de origem (português). */
class TrTest {

    @After
    fun backToPortuguese() {
        AppLocale.language = AppLanguage.PT
    }

    @Test
    fun `saved code resolves the language and blank or unknown falls back to Portuguese`() {
        assertThat(AppLanguage.of("en")).isEqualTo(AppLanguage.EN)
        assertThat(AppLanguage.of("es")).isEqualTo(AppLanguage.ES)
        assertThat(AppLanguage.of("")).isEqualTo(AppLanguage.PT)
        assertThat(AppLanguage.of("fr")).isEqualTo(AppLanguage.PT)
    }

    @Test
    fun `first run follows the device language when it is one of the three, else English`() {
        assertThat(AppLanguage.fromSystem(Locale("pt", "BR"))).isEqualTo(AppLanguage.PT)
        assertThat(AppLanguage.fromSystem(Locale("es", "MX"))).isEqualTo(AppLanguage.ES)
        assertThat(AppLanguage.fromSystem(Locale.GERMANY)).isEqualTo(AppLanguage.EN)
    }

    @Test
    fun `tr switches with the interface language and formats the values`() {
        assertThat(tr("Grade %d", 2)).isEqualTo("Grade 2")
        AppLocale.language = AppLanguage.EN
        assertThat(tr("Grade %d", 2)).isEqualTo("Grid 2")
        AppLocale.language = AppLanguage.ES
        assertThat(tr("Grade %d", 2)).isEqualTo("Cuadrícula 2")
    }

    @Test
    fun `a text without translation falls back to Portuguese`() {
        AppLocale.language = AppLanguage.EN
        assertThat(tr("texto que ninguém traduziu")).isEqualTo("texto que ninguém traduziu")
        assertThat(trKey("Rápido")).isEqualTo("Rápido")
    }
}
