package com.keyflow.orbe

import com.keyflow.core.ButtonState

/** Ponto inteiro em coordenadas da janela do IME (px). */
data class Ponto(val x: Int, val y: Int)

/**
 * Retângulo alinhado aos eixos em pixels (`[esquerda, direita) × [topo, fundo)`), puro (sem `android.graphics.Rect`).
 */
data class Retangulo(
    val esquerda: Int,
    val topo: Int,
    val direita: Int,
    val fundo: Int,
) {
    val largura: Int get() = (direita - esquerda).coerceAtLeast(0)
    val altura: Int get() = (fundo - topo).coerceAtLeast(0)
    val centroX: Int get() = (esquerda + direita) / 2
    val centroY: Int get() = (topo + fundo) / 2
    val vazio: Boolean get() = largura <= 0 || altura <= 0

    fun contem(x: Int, y: Int): Boolean =
        !vazio && x in esquerda until direita && y in topo until fundo

    fun contem(ponto: Ponto): Boolean = contem(ponto.x, ponto.y)

    fun contem(outro: Retangulo): Boolean =
        !vazio && !outro.vazio &&
            outro.esquerda >= esquerda &&
            outro.topo >= topo &&
            outro.direita <= direita &&
            outro.fundo <= fundo

    fun intersecta(outro: Retangulo): Boolean =
        !vazio && !outro.vazio &&
            esquerda < outro.direita &&
            direita > outro.esquerda &&
            topo < outro.fundo &&
            fundo > outro.topo

    fun deslocarX(dx: Int): Retangulo =
        Retangulo(esquerda + dx, topo, direita + dx, fundo)

    fun encolher(margemHorizontalPx: Int, margemVerticalPx: Int = margemHorizontalPx): Retangulo {
        val mh = margemHorizontalPx.coerceAtLeast(0)
        val mv = margemVerticalPx.coerceAtLeast(0)
        val esq = (esquerda + mh).coerceAtMost(centroX)
        val dir = (direita - mh).coerceAtLeast(esq)
        val top = (topo + mv).coerceAtMost(centroY)
        val funPx = (fundo - mv).coerceAtLeast(top)
        return Retangulo(esq, top, dir, funPx)
    }
}

/**
 * Disco da orbe em pixels (KBO-07..09, design §2–3).
 *
 * [contem] avalia a equação do disco `(x - centroX)² + (y - centroY)² <= raio²`, e **não** o retângulo delimitador.
 */
data class Circulo(
    val centroX: Int,
    val centroY: Int,
    val raio: Int,
) {
    val diametro: Int get() = (raio * 2).coerceAtLeast(0)
    val esquerda: Int get() = centroX - raio
    val topo: Int get() = centroY - raio
    val direita: Int get() = centroX + raio
    val fundo: Int get() = centroY + raio
    val vazio: Boolean get() = raio <= 0

    val retanguloDelimitador: Retangulo
        get() = Retangulo(esquerda, topo, direita, fundo)

    fun contem(x: Int, y: Int): Boolean {
        if (raio <= 0) return false
        val dx = (x - centroX).toLong()
        val dy = (y - centroY).toLong()
        val r = raio.toLong()
        return dx * dx + dy * dy <= r * r
    }

    fun contem(ponto: Ponto): Boolean = contem(ponto.x, ponto.y)

    fun cabeEm(retangulo: Retangulo): Boolean =
        !vazio && retangulo.contem(retanguloDelimitador)

    /**
     * Verdadeiro quando o interior do disco cruza o interior de [retangulo].
     * Compartilhar apenas a linha de borda (`fundo == retangulo.topo` ou `direita == retangulo.esquerda`) não é interseção.
     */
    fun intersecta(retangulo: Retangulo): Boolean {
        if (vazio || retangulo.vazio) return false
        if (!retanguloDelimitador.intersecta(retangulo)) return false
        val px = centroX.coerceIn(retangulo.esquerda, retangulo.direita)
        val py = centroY.coerceIn(retangulo.topo, retangulo.fundo)
        val dx = (px - centroX).toLong()
        val dy = (py - centroY).toLong()
        val r = raio.toLong()
        return dx * dx + dy * dy < r * r
    }
}

/**
 * Geometria pura da orbe no encaixe da barra do teclado (KBO-07, KBO-08, KBO-11, design §2).
 *
 * - **Ociosa** (`IDLE_READY`, `IDLE_NO_FIELD`, `PENDING_PASTE` sem Visão ativa): cabe inteira no miolo do encaixe, centrada nele;
 *   nada entra na faixa de 36 dp acima das barras.
 * - **Sobressalente** (`RECORDING`, `PAUSED`, `CANCELLING`, `SENDING`, `ERROR` ou Visão ativa): cresce só para cima, com o fundo
 *   do círculo no fundo do encaixe e `diâmetro = min(largura do encaixe, altura do encaixe + faixa)`.
 */
object GeometriaDaOrbe {
    const val FAIXA_SOBRESSALENTE_DP = 36

    fun ehSobressalente(estado: ButtonState, visaoAtiva: Boolean = false): Boolean {
        if (visaoAtiva) return true
        return when (estado) {
            ButtonState.IDLE_READY,
            ButtonState.IDLE_NO_FIELD,
            ButtonState.PENDING_PASTE -> false
            ButtonState.RECORDING,
            ButtonState.PAUSED,
            ButtonState.CANCELLING,
            ButtonState.SENDING,
            ButtonState.ERROR -> true
        }
    }

    fun ehOciosa(estado: ButtonState, visaoAtiva: Boolean = false): Boolean =
        !ehSobressalente(estado, visaoAtiva)

    /** Posição do encaixe na janela depois de descontar a rolagem horizontal [rolagemX] da barra (KBO-11). */
    fun encaixeRolado(encaixe: Retangulo, rolagemX: Int = 0): Retangulo =
        encaixe.deslocarX(-rolagemX)

    /** Verdadeiro enquanto alguma parte do encaixe ainda está dentro de `[0, larguraBarraPx)`. */
    fun visivelNaBarra(encaixe: Retangulo, larguraBarraPx: Int, rolagemX: Int = 0): Boolean {
        if (larguraBarraPx <= 0) return false
        val rolado = encaixeRolado(encaixe, rolagemX)
        return !rolado.vazio && rolado.direita > 0 && rolado.esquerda < larguraBarraPx
    }

    /** Círculo ocioso: diâmetro = menor lado do miolo do botão, centrado no encaixe (KBO-07). */
    fun ociosa(
        encaixe: Retangulo,
        margemHorizontalPx: Int = 0,
        margemVerticalPx: Int = 0,
        rolagemX: Int = 0,
    ): Circulo {
        val rolado = encaixeRolado(encaixe, rolagemX)
        val miolo = rolado.encolher(margemHorizontalPx, margemVerticalPx)
        val diametro = minOf(miolo.largura, miolo.altura).coerceAtLeast(0)
        val raio = diametro / 2
        return Circulo(
            centroX = rolado.centroX,
            centroY = rolado.centroY,
            raio = raio,
        )
    }

    /**
     * Círculo sobressalente: fundo no fundo do encaixe e `diâmetro = min(largura, altura + faixa)` (KBO-08).
     */
    fun sobressalente(
        encaixe: Retangulo,
        faixaSobressalentePx: Int,
        rolagemX: Int = 0,
    ): Circulo {
        val rolado = encaixeRolado(encaixe, rolagemX)
        val diametro = minOf(
            rolado.largura,
            rolado.altura + faixaSobressalentePx.coerceAtLeast(0),
        ).coerceAtLeast(0)
        val raio = diametro / 2
        return Circulo(
            centroX = rolado.centroX,
            centroY = rolado.fundo - raio,
            raio = raio,
        )
    }

    fun calcular(
        encaixe: Retangulo,
        estado: ButtonState,
        faixaSobressalentePx: Int,
        visaoAtiva: Boolean = false,
        margemHorizontalPx: Int = 0,
        margemVerticalPx: Int = 0,
        rolagemX: Int = 0,
    ): Circulo = if (ehSobressalente(estado, visaoAtiva)) {
        sobressalente(encaixe, faixaSobressalentePx, rolagemX)
    } else {
        ociosa(encaixe, margemHorizontalPx, margemVerticalPx, rolagemX)
    }
}

enum class AlvoDeToque { ORBE, FORA }

/**
 * Hit-test puro da orbe (KBO-09, KBO-11, KBO-34, design §4):
 * - ponto dentro do disco de [circulo], ou dentro de [encaixe] com a orbe [ociosa] → [AlvoDeToque.ORBE];
 * - fora da faixa visível da barra (quando [larguraVisivelPx] é informada e o encaixe rolou para fora) ou fora do disco → [AlvoDeToque.FORA].
 */
object HitDaOrbe {
    fun contem(
        x: Int,
        y: Int,
        circulo: Circulo,
        encaixe: Retangulo? = null,
        ociosa: Boolean = false,
        larguraVisivelPx: Int? = null,
    ): Boolean {
        if (larguraVisivelPx != null) {
            if (x < 0 || x >= larguraVisivelPx) return false
            if (encaixe != null && (encaixe.direita <= 0 || encaixe.esquerda >= larguraVisivelPx)) return false
            if (encaixe == null && (circulo.direita <= 0 || circulo.esquerda >= larguraVisivelPx)) return false
        }
        return circulo.contem(x, y) || (ociosa && encaixe != null && encaixe.contem(x, y))
    }

    fun contem(
        ponto: Ponto,
        circulo: Circulo,
        encaixe: Retangulo? = null,
        ociosa: Boolean = false,
        larguraVisivelPx: Int? = null,
    ): Boolean = contem(ponto.x, ponto.y, circulo, encaixe, ociosa, larguraVisivelPx)

    fun alvo(
        ponto: Ponto,
        circulo: Circulo,
        encaixe: Retangulo? = null,
        ociosa: Boolean = false,
        larguraVisivelPx: Int? = null,
    ): AlvoDeToque = if (contem(ponto, circulo, encaixe, ociosa, larguraVisivelPx)) AlvoDeToque.ORBE else AlvoDeToque.FORA

    fun alvo(
        x: Int,
        y: Int,
        circulo: Circulo,
        encaixe: Retangulo? = null,
        ociosa: Boolean = false,
        larguraVisivelPx: Int? = null,
    ): AlvoDeToque = if (contem(x, y, circulo, encaixe, ociosa, larguraVisivelPx)) AlvoDeToque.ORBE else AlvoDeToque.FORA
}
