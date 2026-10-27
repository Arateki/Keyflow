package com.keyflow.reasoning.local

/** Forma do texto que o template realmente aceita, independente do id ou da revisão dos pesos. */
internal enum class LiteRtTextFormat {
    SINGLE, PARTS;

    /** A segunda parte vazia impede a conversão automática de uma parte em string no LiteRT-LM 0.16.1. */
    fun parts(text: String): List<String> = if (this == PARTS) listOf(text, "") else listOf(text)

    companion object {
        internal const val PROBE = "KeyFlow_verifica_texto_ação_42"

        /** Inspeção sem inferência; o formato antigo tem preferência para preservar os modelos que já funcionam. */
        fun detect(render: (List<String>) -> String): LiteRtTextFormat {
            var failure: Exception? = null
            for (format in entries) {
                try {
                    if (render(format.parts(PROBE)).contains(PROBE)) return format
                } catch (e: Exception) {
                    failure = e
                }
            }
            throw IllegalStateException("o template do modelo descarta o texto do pedido", failure)
        }
    }
}
