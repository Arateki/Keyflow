package com.keyflow.i18n

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Toda frase marcada com `tr("…")`/`trKey("…")` no código tem inglês e espanhol, com os mesmos `%s`/`%d`, e
 * nenhuma tradução sobra sem uso. Lê o próprio código-fonte: é assim que uma frase nova sem tradução
 * aparece aqui, e não em produção como português no meio de uma tela em inglês.
 */
class TranslationsTest {

    private val keys: Set<String> by lazy {
        val call = Regex("""(?<![A-Za-z0-9_])tr(?:Key)?\(\s*"((?:[^"\\]|\\.)*)"""", RegexOption.MULTILINE)
        listOf(File("src/main/java"), File("src/debug/java")).asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" && it.name != "Tr.kt" }
            .flatMap { file -> call.findAll(file.readText()).map { unescape(it.groupValues[1]) } }
            .toSet()
    }

    @Test
    fun `the source has texts marked for translation`() {
        assertWithMessage("nenhum tr(\"…\") achado — o teste está lendo o diretório certo?").that(keys.size).isGreaterThan(300)
    }

    @Test
    fun `every marked text has English and Spanish`() {
        assertWithMessage("sem inglês").that(keys - TranslationsEn.keys).isEmpty()
        assertWithMessage("sem espanhol").that(keys - TranslationsEs.keys).isEmpty()
    }

    @Test
    fun `translations keep the same placeholders`() {
        for ((name, table) in listOf("inglês" to TranslationsEn, "espanhol" to TranslationsEs)) {
            for ((pt, translated) in table) {
                assertWithMessage("%s de \"%s\"", name, pt).that(placeholders(translated)).isEqualTo(placeholders(pt))
            }
        }
    }

    @Test
    fun `no translation is left without a text in the source`() {
        assertWithMessage("inglês sobrando").that(TranslationsEn.keys - keys).isEmpty()
        assertWithMessage("espanhol sobrando").that(TranslationsEs.keys - keys).isEmpty()
    }

    private fun placeholders(text: String): List<String> = Regex("%[sd%]").findAll(text).map { it.value }.toList()

    private fun unescape(literal: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < literal.length) {
            val c = literal[i]
            if (c != '\\' || i + 1 >= literal.length) {
                out.append(c); i++; continue
            }
            when (val n = literal[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'u' -> {
                    out.append(literal.substring(i + 2, i + 6).toInt(16).toChar()); i += 4
                }
                else -> out.append(n) // \" \\ \$
            }
            i += 2
        }
        return out.toString()
    }
}
