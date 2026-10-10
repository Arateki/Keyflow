package com.keyflow.skins

import com.keyflow.i18n.tr
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** O que um SVG usa e o app não desenha (FORMAT.md §4): vira **aviso** no relatório, não erro. */
internal enum class SvgWarning { FILTER, BLEND_MODE, SMIL_ANIMATION, CSS_ANIMATION, SVG_FONT }

/** O resultado da conferência textual de um SVG: [errors] já citam o arquivo; [warnings] são agregados por quem chama. */
internal class SvgTextCheck(val errors: List<String>, val warnings: Set<SvgWarning>)

/**
 * A recusa **textual** de SVG perigoso, **antes** de qualquer parse (SKP-IMP-04). Puro (JVM): nada de `android.graphics`.
 *
 * O parser da biblioteca nunca vê um `DOCTYPE`/entidade, `<script>`, `<foreignObject>` nem referência externa: o que não é
 * `#id` interno ou `data:image/png|jpeg;base64` é erro. A conferência é por texto e **conservadora de propósito**: um `<script`
 * dentro de um comentário também é recusado (melhor um falso erro, com o nome do arquivo, do que uma brecha). Como o `DOCTYPE`
 * é recusado, não há entidade que possa esconder um `href` — e um valor com `&` é recusado por não poder ser verificado.
 */
internal object SvgTextChecker {

    fun check(fileName: String, bytes: ByteArray): SvgTextCheck {
        if (bytes.isEmpty()) return SvgTextCheck(listOf(tr("%s: o arquivo está vazio.", fileName)), emptySet())
        val text = decode(bytes) ?: return SvgTextCheck(listOf(tr("%s: o arquivo não está em UTF-8.", fileName)), emptySet())

        val errors = mutableListOf<String>()
        if (!SVG_TAG.containsMatchIn(text)) errors += tr("%s: não parece um SVG (não tem a tag <svg>).", fileName)
        if (DOCTYPE.containsMatchIn(text)) {
            errors += tr("%s: o SVG tem DOCTYPE ou entidades, que não são aceitos por segurança — remova a linha <!DOCTYPE …>.", fileName)
        }
        if (SCRIPT.containsMatchIn(text)) errors += tr("%s: o SVG tem <script>, que não é aceito por segurança.", fileName)
        if (FOREIGN_OBJECT.containsMatchIn(text)) errors += tr("%s: o SVG tem <foreignObject>, que não é aceito por segurança.", fileName)
        if (STYLESHEET_PI.containsMatchIn(text) || CSS_IMPORT.containsMatchIn(text)) {
            errors += tr("%s: o SVG carrega uma folha de estilo externa (xml-stylesheet ou @import), que não é aceita.", fileName)
        }
        externalReference(text)?.let {
            errors += tr("%s: o SVG tem uma referência externa (%s) — só #id e imagens data:image/png ou jpeg em base64 são aceitos.", fileName, it)
        }

        val warnings = buildSet {
            if (FILTER.containsMatchIn(text)) add(SvgWarning.FILTER)
            if (BLEND_MODE.containsMatchIn(text)) add(SvgWarning.BLEND_MODE)
            if (SMIL.containsMatchIn(text)) add(SvgWarning.SMIL_ANIMATION)
            if (CSS_ANIMATION.containsMatchIn(text)) add(SvgWarning.CSS_ANIMATION)
            if (SVG_FONT.containsMatchIn(text)) add(SvgWarning.SVG_FONT)
        }
        return SvgTextCheck(errors, warnings)
    }

    /** UTF-8 estrito (BOM tolerado); `null` se os bytes não são UTF-8 válido. */
    private fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix("﻿")
    } catch (_: CharacterCodingException) {
        null
    }

    /** O primeiro `href`/`xlink:href`/`url(…)` que não é `#id` interno nem imagem `data:` PNG/JPEG em base64 (já encurtado), ou `null`. */
    private fun externalReference(text: String): String? {
        for (match in HREF.findAll(text)) {
            val value = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (!isAllowedReference(value)) return shorten(value)
        }
        for (match in URL_FUNCTION.findAll(text)) {
            val value = match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } }
            if (!isAllowedReference(value)) return shorten(value)
        }
        return null
    }

    private fun isAllowedReference(raw: String): Boolean {
        val value = raw.trim()
        // Um `&` (entidade ou referência numérica) esconderia o destino: não dá para conferir, então não passa.
        if (value.isEmpty() || '&' in value) return false
        return value.startsWith("#") || DATA_IMAGE.containsMatchIn(value)
    }

    private fun shorten(value: String): String = if (value.length <= 48) "\"$value\"" else "\"${value.take(45)}…\""

    private const val PREFIX = """(?:[A-Za-z_][\w.\-]*:)?"""
    private val SVG_TAG = Regex("""<$PREFIX""" + """svg[\s/>]""")
    private val DOCTYPE = Regex("""<!\s*(?:DOCTYPE|ENTITY)""", RegexOption.IGNORE_CASE)
    private val SCRIPT = Regex("""<$PREFIX""" + """script(?=[\s/>])""", RegexOption.IGNORE_CASE)
    private val FOREIGN_OBJECT = Regex("""<$PREFIX""" + """foreignObject(?=[\s/>])""", RegexOption.IGNORE_CASE)
    private val STYLESHEET_PI = Regex("""<\?xml-stylesheet""", RegexOption.IGNORE_CASE)
    private val CSS_IMPORT = Regex("""@import\b""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""(?:^|[\s"'])$PREFIX""" + """href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val URL_FUNCTION = Regex("""url\(\s*(?:"([^"]*)"|'([^']*)'|([^)\s'"]*))\s*\)""", RegexOption.IGNORE_CASE)
    private val DATA_IMAGE = Regex("""^data:image/(?:png|jpeg);base64,[A-Za-z0-9+/=\s]*$""", RegexOption.IGNORE_CASE)

    private val FILTER = Regex("""<$PREFIX""" + """filter[\s/>]|[\s;"'{]filter\s*[:=]""")
    private val BLEND_MODE = Regex("""mix-blend-mode""")
    private val SMIL = Regex("""<$PREFIX""" + """(?:animate|animateTransform|animateMotion|animateColor|set)[\s/>]""")
    private val CSS_ANIMATION = Regex("""@keyframes|[\s;"'{]animation(?:-name)?\s*:""")
    private val SVG_FONT = Regex("""<$PREFIX""" + """(?:font|font-face|glyph)[\s/>]""")
}
