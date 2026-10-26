package com.keyflow.reasoning.local

/**
 * Tira o raciocínio interno de modelos cujo prompt JÁ abre o `<think>` (DeepSeek R1: o metadado do `.litertlm` termina o
 * turno do modelo em `<｜Assistant｜><think>\n`). A saída então começa DENTRO do pensamento e só traz o `</think>` — o canal
 * "thought" do LiteRT-LM, que espera o `<think>` de abertura, não o reconhece. Aqui: nada sai até o primeiro `</think>`; o
 * que vem depois é a resposta (sem a quebra de linha inicial). Sem `</think>` ([closed] falso no fim), o texto cru NUNCA
 * vira resposta: quem chama trata como falha.
 *
 * Com [opensThink] falso é transparente (os modelos que abrem o `<think>` sozinhos ficam com o canal do LiteRT-LM).
 * Puro Kotlin, sem a biblioteca: testável na JVM.
 */
internal class ThoughtStripper(private val opensThink: Boolean) {

    private var inside = opensThink
    private var tail = ""
    private var started = false

    /** Já passou do `</think>` (ou o modelo não abre o `<think>`): o que sai de [accept] é resposta. */
    val closed: Boolean get() = !inside

    /** O trecho de resposta que este [chunk] libera (pode ser vazio). */
    fun accept(chunk: String): String {
        if (!inside) return answer(chunk)
        val buffer = tail + chunk
        val at = buffer.indexOf(CLOSE)
        if (at < 0) {
            // Guarda só o fim, para um `</think>` partido entre dois trechos; o pensamento em si é descartado.
            tail = buffer.takeLast(CLOSE.length - 1)
            return ""
        }
        inside = false
        tail = ""
        return answer(buffer.substring(at + CLOSE.length))
    }

    /** Depois do `</think>`, a resposta começa no primeiro caractere que não é espaço ou quebra de linha. */
    private fun answer(text: String): String {
        if (started) return text
        val trimmed = text.trimStart()
        if (trimmed.isNotEmpty()) started = true
        return if (opensThink) trimmed else text
    }

    private companion object {
        const val CLOSE = "</think>"
    }
}
