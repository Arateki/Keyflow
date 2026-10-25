package com.keyflow.ui.config

import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fonte única da documentação (aba Docs do app e página `/doc` do site).
 *
 * O texto mora em [docsSections], [docsSteps] e nas traduções do app, como sempre. Daqui sai o
 * `website/_fonte/doc.json`, que o `scripts/gerar-site.py` lê para montar as páginas `/doc` (português) e `/docs`
 * (inglês). Como o site é publicado por um CI sem Gradle, o JSON fica versionado — e este teste falha se ele não
 * bater com o que o app diz agora. Ou seja: mexeu no texto da Docs, o teste mostra o que atualizar; ninguém edita o
 * texto do site à mão, e o site não consegue ficar para trás do app.
 *
 * Para atualizar o arquivo depois de mudar o texto:
 * `KEYFLOW_REGERAR_DOC_SITE=1 gradle testDebugUnitTest --tests '*DocsSiteExportTest'` (ou copie
 * `app/build/docs-site/doc.json`, que toda execução grava).
 */
class DocsSiteExportTest {

    /** O teste de unidade roda com o diretório do módulo `app/` como diretório de trabalho. */
    private val arquivoDoSite = File("../website/_fonte/doc.json")
    private val copiaGerada = File("build/docs-site/doc.json")

    @Test
    fun `o JSON da documentacao do site bate com a aba Docs do app`() {
        val esperado = docsSiteJson()
        copiaGerada.absoluteFile.parentFile!!.mkdirs()
        copiaGerada.writeText(esperado)

        if (System.getenv("KEYFLOW_REGERAR_DOC_SITE") == "1") {
            arquivoDoSite.absoluteFile.parentFile!!.mkdirs()
            arquivoDoSite.writeText(esperado)
            return
        }

        assertTrue("não achei ${arquivoDoSite.absolutePath} (rode a partir do módulo app/)", arquivoDoSite.absoluteFile.parentFile!!.isDirectory)
        val atual = if (arquivoDoSite.isFile) arquivoDoSite.readText() else ""
        if (atual != esperado) {
            val a = atual.lines()
            val e = esperado.lines()
            val i = (0 until maxOf(a.size, e.size)).first { a.getOrNull(it) != e.getOrNull(it) }
            fail(
                "website/_fonte/doc.json está desatualizado em relação à aba Docs do app (primeira diferença na linha ${i + 1}).\n" +
                    "  no arquivo : ${a.getOrNull(i)?.take(220)}\n" +
                    "  no app     : ${e.getOrNull(i)?.take(220)}\n" +
                    "Atualize com: KEYFLOW_REGERAR_DOC_SITE=1 gradle testDebugUnitTest --tests '*DocsSiteExportTest'\n" +
                    "(ou copie ${copiaGerada.absolutePath} para ${arquivoDoSite.path}) e commite junto com a mudança do texto.",
            )
        }
    }

    @Test
    fun `o JSON traz as duas linguas do site com as mesmas secoes`() {
        val pt = docsData(AppLanguage.PT)
        val en = docsData(AppLanguage.EN)
        assertTrue(pt.sections.size > 10)
        assertTrue("seções diferentes entre português e inglês", pt.sections.map { it.id } == en.sections.map { it.id })
        assertTrue("passos diferentes entre português e inglês", pt.steps.size == en.steps.size)
        pt.sections.zip(en.sections).forEach { (p, e) ->
            assertTrue("blocos de '${p.id}' diferem entre os idiomas", p.blocks.map { it::class } == e.blocks.map { it::class })
        }
    }

    // ── Dados e serialização ──────────────────────────────────────────────────────────────────────

    private class DocsData(val howTitle: String, val indexTitle: String, val steps: List<DocStep>, val sections: List<DocSection>)

    /** Lê os textos da Docs no idioma [lang] — `tr` e `pauseHintFor` falam o idioma fixado em [AppLocale.pinned]. */
    private fun docsData(lang: AppLanguage): DocsData {
        val antes = AppLocale.pinned
        AppLocale.pinned = lang
        try {
            return DocsData(docsHowItWorksTitle, docsIndexTitle, docsSteps, docsSections)
        } finally {
            AppLocale.pinned = antes
        }
    }

    private fun docsSiteJson(): String {
        val idiomas = linkedMapOf<String, Any?>()
        for ((chave, lang) in listOf("pt" to AppLanguage.PT, "en" to AppLanguage.EN)) {
            val d = docsData(lang)
            idiomas[chave] = linkedMapOf(
                "comoFunciona" to linkedMapOf(
                    "titulo" to d.howTitle,
                    "passos" to d.steps.map { linkedMapOf("numero" to it.number, "titulo" to it.title, "texto" to it.body) },
                ),
                "indice" to d.indexTitle,
                "secoes" to d.sections.map { s ->
                    val m = linkedMapOf<String, Any?>("id" to s.id, "titulo" to s.title)
                    if (s.feedbackActions) m["feedback"] = true
                    m["blocos"] = s.blocks.map { b ->
                        when (b) {
                            is DocBlock.Paragraph -> linkedMapOf("tipo" to "paragrafo", "texto" to b.text)
                            is DocBlock.Labeled -> linkedMapOf("tipo" to "rotulado", "rotulo" to b.label, "texto" to b.text)
                        }
                    }
                    m
                },
            )
        }
        val raiz = linkedMapOf<String, Any?>(
            "gerado_por" to "app/src/test/java/com/keyflow/ui/config/DocsSiteExportTest.kt — não edite à mão: o texto vem de DocsPanel.kt e das traduções do app",
            "idiomas" to idiomas,
        )
        return StringBuilder().also { writeJson(it, raiz, 0) }.append('\n').toString()
    }

    /** JSON com 2 espaços de recuo; objeto só de valores simples (bloco, passo) cabe numa linha, para o diff ficar legível. */
    private fun writeJson(out: StringBuilder, value: Any?, level: Int) {
        val pad = "  ".repeat(level)
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is String -> out.append(jsonString(value))
            is Map<*, *> -> if (value.values.all { it == null || it is String || it is Boolean }) {
                out.append(value.entries.joinToString(", ", "{", "}") { (k, v) -> "${jsonString(k as String)}: ${jsonScalar(v)}" })
            } else {
                out.append("{\n")
                value.entries.forEachIndexed { i, (k, v) ->
                    out.append(pad).append("  ").append(jsonString(k as String)).append(": ")
                    writeJson(out, v, level + 1)
                    out.append(if (i < value.size - 1) ",\n" else "\n")
                }
                out.append(pad).append("}")
            }
            is List<*> -> {
                out.append("[\n")
                value.forEachIndexed { i, v ->
                    out.append(pad).append("  ")
                    writeJson(out, v, level + 1)
                    out.append(if (i < value.size - 1) ",\n" else "\n")
                }
                out.append(pad).append("]")
            }
            else -> error("tipo sem JSON: ${value::class}")
        }
    }

    private fun jsonScalar(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        else -> jsonString(v as String)
    }

    /** Escapa aspas, barra, controle e caracteres invisíveis (como o U+2060 de "?⁠123"): o arquivo fica legível em revisão. */
    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c.code < 0x20 || Character.getType(c) == Character.FORMAT.toInt() ||
                    (Character.isSpaceChar(c) && c != ' ') -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
