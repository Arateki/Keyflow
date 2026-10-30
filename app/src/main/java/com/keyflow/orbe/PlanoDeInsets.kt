package com.keyflow.orbe

sealed interface RegiaoToque {
    data object SoConteudo : RegiaoToque
    data class ConteudoMaisCirculo(val circulo: Circulo) : RegiaoToque
    data object JanelaInteira : RegiaoToque
}

/** Contrato da janela, separado do hit-test interno do teclado (KBO-09). */
data class PlanoDeInsets(val contentTopPx: Int, val visibleTopPx: Int, val regiao: RegiaoToque) {
    fun contem(ponto: Ponto, larguraPx: Int, alturaPx: Int): Boolean {
        if (!Retangulo(0, 0, larguraPx, alturaPx).contem(ponto)) return false
        return regiao == RegiaoToque.JanelaInteira || ponto.y >= contentTopPx ||
            (regiao as? RegiaoToque.ConteudoMaisCirculo)?.circulo?.contem(ponto) == true
    }

    companion object {
        fun calcular(
            overscanPx: Int,
            sobressairPx: Int,
            temEncaixe: Boolean,
            circuloSobressalente: Circulo? = null,
            gradeEmArrasto: Boolean = false,
        ): PlanoDeInsets {
            val topo = overscanPx.coerceAtLeast(0) + if (temEncaixe) sobressairPx.coerceAtLeast(0) else 0
            val regiao = when {
                gradeEmArrasto -> RegiaoToque.JanelaInteira
                temEncaixe && circuloSobressalente != null && !circuloSobressalente.vazio ->
                    RegiaoToque.ConteudoMaisCirculo(circuloSobressalente)
                else -> RegiaoToque.SoConteudo
            }
            return PlanoDeInsets(topo, topo, regiao)
        }
    }
}

/** Soma a faixa antes do teto de segurança que já limita a reserva da grade (KBO-10). */
fun reservaComFaixaPx(conteudoPx: Float, sobressairPx: Int, alturaUtilPx: Float): Float =
    minOf(conteudoPx + sobressairPx.coerceAtLeast(0), alturaUtilPx * 0.75f)
