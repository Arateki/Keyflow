package com.keyflow.orbe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlanoDeInsetsTest {
    private val disco = Circulo(32, 32, 32)
    private fun plano(overscan: Int = 0, encaixe: Boolean = true, ativo: Boolean = false, arrasto: Boolean = false) =
        PlanoDeInsets.calcular(overscan, 36, encaixe, disco.takeIf { ativo }, arrasto)

    @Test fun `content e visible descontam juntos a faixa em todos os casos`() {
        for (overscan in listOf(0, 140)) for (encaixe in listOf(false, true)) {
            for (ativo in listOf(false, true)) for (arrasto in listOf(false, true)) {
                val p = plano(overscan, encaixe, ativo, arrasto)
                assertThat(p.contentTopPx).isEqualTo(overscan + if (encaixe) 36 else 0)
                assertThat(p.visibleTopPx).isEqualTo(p.contentTopPx)
            }
        }
    }

    @Test fun `ociosa deixa a faixa atravessar e conserva o conteudo`() {
        val p = plano()
        assertThat(p.regiao).isEqualTo(RegiaoToque.SoConteudo)
        assertThat(p.contem(Ponto(32, 20), 100, 200)).isFalse()
        assertThat(p.contem(Ponto(1, 36), 100, 200)).isTrue()
    }

    @Test fun `sobressalente une o disco e rejeita contraexemplo e cantos acima do conteudo`() {
        val p = plano(ativo = true)
        assertThat(p.regiao).isEqualTo(RegiaoToque.ConteudoMaisCirculo(disco))
        for (ponto in listOf(Ponto(1, 1), Ponto(0, 0), Ponto(63, 0))) {
            assertThat(p.contem(ponto, 100, 200)).isFalse()
        }
        assertThat(p.contem(Ponto(32, 1), 100, 200)).isTrue()
        // Canto inferior fora do disco pertence ao conteúdo e permanece tocável.
        assertThat(disco.contem(Ponto(1, 63))).isFalse()
        assertThat(p.contem(Ponto(1, 63), 100, 200)).isTrue()
    }

    @Test fun `regiao fica recortada a janela mesmo em arrasto`() {
        for (p in listOf(plano(ativo = true), plano(arrasto = true))) {
            for (ponto in listOf(Ponto(-1, 32), Ponto(100, 50), Ponto(32, -1), Ponto(1, 200))) {
                assertThat(p.contem(ponto, 100, 200)).isFalse()
            }
        }
        assertThat(plano(arrasto = true).regiao).isEqualTo(RegiaoToque.JanelaInteira)
        assertThat(plano(arrasto = true).contem(Ponto(1, 1), 100, 200)).isTrue()
    }

    @Test fun `sem encaixe nao ha faixa nem disco`() {
        assertThat(plano(encaixe = false, ativo = true)).isEqualTo(PlanoDeInsets(0, 0, RegiaoToque.SoConteudo))
    }

    @Test fun `reserva soma 36 dp antes do teto existente`() {
        for (densidade in listOf(1f, 2f, 3f)) {
            val faixa = (36 * densidade).toInt()
            assertThat(reservaComFaixaPx(240f, faixa, 2000f))
                .isEqualTo(reservaComFaixaPx(240f, 0, 2000f) + faixa)
        }
        assertThat(reservaComFaixaPx(740f, 36, 1000f)).isEqualTo(750f)
        assertThat(reservaComFaixaPx(900f, 36, 1000f)).isEqualTo(750f)
    }
}
