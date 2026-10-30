package com.keyflow.orbe

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import org.junit.Test

/** Geometria pura e hit-test da orbe no encaixe da barra do teclado (KBO-07, KBO-08, KBO-11, KBO-34). */
class GeometriaDaOrbeTest {

    // Densidade 1 (contraexemplo de KBO-09 / KBO-T1): faixa 36, encaixe 64×28 logo abaixo da faixa.
    private val faixa = 36
    private val faixaRect = Retangulo(esquerda = 0, topo = 0, direita = 1080, fundo = faixa)
    private val encaixePadrao = Retangulo(esquerda = 0, topo = 36, direita = 64, fundo = 64)

    @Test
    fun `estados ociosos e sobressalentes seguem KBO-07 e KBO-08`() {
        val ociosos = listOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD, ButtonState.PENDING_PASTE)
        val sobressalentes = listOf(
            ButtonState.RECORDING,
            ButtonState.PAUSED,
            ButtonState.CANCELLING,
            ButtonState.SENDING,
            ButtonState.ERROR,
        )
        for (estado in ociosos) {
            assertThat(GeometriaDaOrbe.ehOciosa(estado)).isTrue()
            assertThat(GeometriaDaOrbe.ehSobressalente(estado)).isFalse()
            // Com Visão ativa, mesmo em estado ocioso de gravação, a orbe fica sobressalente (KBO-08).
            assertThat(GeometriaDaOrbe.ehSobressalente(estado, visaoAtiva = true)).isTrue()
        }
        for (estado in sobressalentes) {
            assertThat(GeometriaDaOrbe.ehSobressalente(estado)).isTrue()
            assertThat(GeometriaDaOrbe.ehOciosa(estado)).isFalse()
        }
    }

    @Test
    fun `ociosa cabe no encaixe e no miolo e nao intersecta a faixa de 36 dp`() {
        val semMargem = GeometriaDaOrbe.ociosa(encaixePadrao)
        assertThat(semMargem.diametro).isEqualTo(28)
        assertThat(semMargem.cabeEm(encaixePadrao)).isTrue()
        assertThat(semMargem.intersecta(faixaRect)).isFalse()
        assertThat(semMargem.topo).isAtLeast(faixa)

        val comMargem = GeometriaDaOrbe.ociosa(encaixePadrao, margemHorizontalPx = 3, margemVerticalPx = 2)
        val miolo = encaixePadrao.encolher(3, 2)
        assertThat(comMargem.diametro).isEqualTo(24)
        assertThat(comMargem.cabeEm(miolo)).isTrue()
        assertThat(comMargem.cabeEm(encaixePadrao)).isTrue()
        assertThat(comMargem.intersecta(faixaRect)).isFalse()
    }

    @Test
    fun `sobressalente tem fundo no fundo do encaixe e e disjunta da tecla abaixo e do vizinho na mesma barra`() {
        val circulo = GeometriaDaOrbe.sobressalente(encaixePadrao, faixaSobressalentePx = faixa)
        assertThat(circulo).isEqualTo(Circulo(centroX = 32, centroY = 32, raio = 32))
        assertThat(circulo.fundo).isEqualTo(encaixePadrao.fundo)
        assertThat(circulo.diametro).isAtMost(encaixePadrao.largura)
        assertThat(circulo.intersecta(faixaRect)).isTrue()

        // Tecla logo abaixo da barra (começa em y = 64) e vizinho imediatamente à direita (começa em x = 64).
        val teclaAbaixo = Retangulo(esquerda = 0, topo = 64, direita = 96, fundo = 120)
        val botaoVizinhoDireito = Retangulo(esquerda = 64, topo = 36, direita = 128, fundo = 64)
        val botaoVizinhoEsquerdo = Retangulo(esquerda = -64, topo = 36, direita = 0, fundo = 64)
        assertThat(circulo.intersecta(teclaAbaixo)).isFalse()
        assertThat(circulo.intersecta(botaoVizinhoDireito)).isFalse()
        assertThat(circulo.intersecta(botaoVizinhoEsquerdo)).isFalse()
    }

    @Test
    fun `diametro nunca passa da largura do encaixe e encaixe estreito sobressai menos que 36 dp`() {
        val estreito = Retangulo(esquerda = 10, topo = 36, direita = 50, fundo = 64) // 40×28
        val circulo = GeometriaDaOrbe.sobressalente(estreito, faixaSobressalentePx = faixa)
        assertThat(circulo.diametro).isEqualTo(40)
        assertThat(circulo.diametro).isAtMost(estreito.largura)
        assertThat(circulo.fundo).isEqualTo(estreito.fundo)
        val quantoSobressaiu = estreito.topo - circulo.topo
        assertThat(quantoSobressaiu).isEqualTo(12)
        assertThat(quantoSobressaiu).isLessThan(faixa)

        // Encaixe largo (90×28): limitado por altura + 36 dp = 64.
        val largo = Retangulo(esquerda = 0, topo = 36, direita = 90, fundo = 64)
        val circuloLargo = GeometriaDaOrbe.sobressalente(largo, faixaSobressalentePx = faixa)
        assertThat(circuloLargo.diametro).isEqualTo(64)
        assertThat(circuloLargo.diametro).isAtMost(largo.largura)
        assertThat(circuloLargo.fundo).isEqualTo(largo.fundo)
        assertThat(largo.topo - circuloLargo.topo).isEqualTo(faixa)
    }

    @Test
    fun `hit-test aceita ponto no circulo, rejeita ponto na tecla e rejeita o contraexemplo (1, 1)`() {
        val circulo = GeometriaDaOrbe.sobressalente(encaixePadrao, faixaSobressalentePx = faixa)
        assertThat(circulo).isEqualTo(Circulo(centroX = 32, centroY = 32, raio = 32))

        // Contraexemplo KBO-09 / KBO-T1: (1, 1) está no retângulo delimitador [0..64)×[0..64), mas fora do disco (1922 > 1024).
        assertThat(circulo.retanguloDelimitador.contem(1, 1)).isTrue()
        assertThat(HitDaOrbe.contem(1, 1, circulo, encaixePadrao, ociosa = false)).isFalse()
        assertThat(HitDaOrbe.alvo(Ponto(1, 1), circulo, encaixePadrao, ociosa = false)).isEqualTo(AlvoDeToque.FORA)

        // Cantos do retângulo delimitador fora do disco também são rejeitados.
        for (canto in listOf(Ponto(0, 0), Ponto(63, 0), Ponto(0, 63), Ponto(63, 63))) {
            assertThat(HitDaOrbe.contem(canto, circulo, encaixePadrao, ociosa = false)).isFalse()
        }

        // Ponto no disco (na faixa de 36 dp e no centro) é da orbe.
        assertThat(HitDaOrbe.contem(32, 10, circulo, encaixePadrao, ociosa = false)).isTrue()
        assertThat(HitDaOrbe.alvo(Ponto(32, 32), circulo, encaixePadrao, ociosa = false)).isEqualTo(AlvoDeToque.ORBE)

        // Ponto numa tecla (y >= 64) ou num botão vizinho (x >= 64) não é da orbe.
        assertThat(HitDaOrbe.contem(32, 70, circulo, encaixePadrao, ociosa = false)).isFalse()
        assertThat(HitDaOrbe.contem(70, 50, circulo, encaixePadrao, ociosa = false)).isFalse()
    }

    @Test
    fun `ociosa aceita toque em todo o encaixe e rejeita fora dele`() {
        val circuloOcioso = GeometriaDaOrbe.ociosa(encaixePadrao, margemHorizontalPx = 3, margemVerticalPx = 2)
        // Canto do encaixe fora do pequeno disco ocioso ainda é alvo quando ociosa (KBO-34, design §4).
        assertThat(circuloOcioso.contem(2, 38)).isFalse()
        assertThat(HitDaOrbe.contem(2, 38, circuloOcioso, encaixePadrao, ociosa = true)).isTrue()
        assertThat(HitDaOrbe.contem(2, 20, circuloOcioso, encaixePadrao, ociosa = true)).isFalse()
    }

    @Test
    fun `orbe acompanha a rolagem horizontal da barra e sai junto quando o encaixe rola para fora`() {
        val encaixeNaBarra = Retangulo(esquerda = 20, topo = 36, direita = 84, fundo = 64)
        val rolado = GeometriaDaOrbe.sobressalente(encaixeNaBarra, faixaSobressalentePx = faixa, rolagemX = 15)
        assertThat(rolado.centroX).isEqualTo(52 - 15)

        // Rolou 100 px para a direita: encaixe foi para [-80, -16), fora da área visível [0, 360).
        val fora = GeometriaDaOrbe.sobressalente(encaixeNaBarra, faixaSobressalentePx = faixa, rolagemX = 100)
        val encaixeFora = GeometriaDaOrbe.encaixeRolado(encaixeNaBarra, rolagemX = 100)
        assertThat(GeometriaDaOrbe.visivelNaBarra(encaixeNaBarra, larguraBarraPx = 360, rolagemX = 100)).isFalse()
        assertThat(HitDaOrbe.contem(20, 40, fora, encaixeFora, ociosa = false, larguraVisivelPx = 360)).isFalse()
    }
}
