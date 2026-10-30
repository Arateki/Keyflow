package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ShortcutOverflow
import com.keyflow.orbe.ResolucaoDeFluxo
import org.junit.Test

/** NTF-T4: da rajada assentada à ação (NTF-10, NTF-11, NTF-39, design §5). */
class ResolvedorDeInteracaoTest {

    private fun ef(a1: AcaoDaOrbe = AcaoDaOrbe.GravarEnviar, a2: AcaoDaOrbe = AcaoDaOrbe.RetrairExpandirTeclado, a3: AcaoDaOrbe = AcaoDaOrbe.TrocarTeclado) =
        efetiva(ConfigNotificacao(copiarDaOrbePrincipal = false, acao1Toque = a1, acao2Toques = a2, acao3Toques = a3), com.keyflow.data.Settings())

    private fun r(
        toques: Int,
        e: ConfigEfetiva = ef(),
        visao: Boolean = false,
        teclado: Boolean = true,
        overflow: ShortcutOverflow = ShortcutOverflow.NEAREST_DOWN,
        fluxos: GanchoDeFluxoDaNotificacao = { null },
    ) = resolver(toques, visao, teclado, e, overflow, fluxos)

    @Test
    fun `cada toque executa a acao da sua interacao`() {
        assertThat(r(1)).isEqualTo(Comando.GravarEnviar)
        assertThat(r(2)).isEqualTo(Comando.Executar(AcaoDaOrbe.RetrairExpandirTeclado))
        assertThat(r(3)).isEqualTo(Comando.Executar(AcaoDaOrbe.TrocarTeclado))
        assertThat(r(0)).isEqualTo(Comando.Nada)
    }

    @Test
    fun `o toque remapeado para GravarEnviar grava, esteja em 1, 2 ou 3`() {
        assertThat(r(2, ef(a1 = AcaoDaOrbe.Nenhuma, a2 = AcaoDaOrbe.GravarEnviar))).isEqualTo(Comando.GravarEnviar)
        assertThat(r(3, ef(a1 = AcaoDaOrbe.Nenhuma, a3 = AcaoDaOrbe.GravarEnviar))).isEqualTo(Comando.GravarEnviar)
        assertThat(r(1, ef(a1 = AcaoDaOrbe.LerTela))).isEqualTo(Comando.Executar(AcaoDaOrbe.LerTela))
    }

    @Test
    fun `um toque em Nenhuma nao faz nada e zero toques em GravarEnviar e aceito sem inventar a acao`() {
        val semGravar = ef(a1 = AcaoDaOrbe.Nenhuma, a2 = AcaoDaOrbe.Nenhuma, a3 = AcaoDaOrbe.Nenhuma)
        assertThat(semGravar.iniciaGravacao).isFalse()
        for (n in 1..5) assertThat(r(n, semGravar)).isEqualTo(Comando.Nada)
    }

    @Test
    fun `retrair so com o teclado visivel`() {
        assertThat(r(2, teclado = false)).isEqualTo(Comando.Nada)
        assertThat(r(2, teclado = true)).isEqualTo(Comando.Executar(AcaoDaOrbe.RetrairExpandirTeclado))
        assertThat(r(3, teclado = false)).isEqualTo(Comando.Executar(AcaoDaOrbe.TrocarTeclado))
    }

    @Test
    fun `com a Visao ativa so GravarEnviar age`() {
        assertThat(r(1, visao = true)).isEqualTo(Comando.GravarEnviar)
        assertThat(r(2, visao = true)).isEqualTo(Comando.Nada)
        assertThat(r(3, visao = true)).isEqualTo(Comando.Nada)
        assertThat(r(1, ef(a1 = AcaoDaOrbe.LerTela), visao = true)).isEqualTo(Comando.Nada)
        assertThat(r(2, ef(a2 = AcaoDaOrbe.GravarEnviar), visao = true)).isEqualTo(Comando.GravarEnviar)
        // o gancho de fluxo também não age com a Visão ativa
        assertThat(r(2, ef(a2 = AcaoDaOrbe.GravarEnviar), visao = true, fluxos = { ResolucaoDeFluxo.Disparar("f") })).isEqualTo(Comando.GravarEnviar)
        assertThat(r(3, visao = true, fluxos = { ResolucaoDeFluxo.Disparar("f") })).isEqualTo(Comando.Nada)
    }

    @Test
    fun `NEAREST_DOWN com cinco toques cai no 3, ou no 2 se o 3 e Nenhuma`() {
        assertThat(r(5)).isEqualTo(Comando.Executar(AcaoDaOrbe.TrocarTeclado))
        assertThat(r(4, ef(a3 = AcaoDaOrbe.Nenhuma))).isEqualTo(Comando.Executar(AcaoDaOrbe.RetrairExpandirTeclado))
    }

    @Test
    fun `o 1 toque fica fora do overflow`() {
        assertThat(r(5, ef(a2 = AcaoDaOrbe.Nenhuma, a3 = AcaoDaOrbe.Nenhuma))).isEqualTo(Comando.Nada)
    }

    @Test
    fun `overflow CANCEL nao faz nada`() {
        assertThat(r(4, overflow = ShortcutOverflow.CANCEL)).isEqualTo(Comando.Nada)
    }

    @Test
    fun `acoes inertes no overflow sao puladas`() {
        val e = ef(a2 = AcaoDaOrbe.LerTela, a3 = AcaoDaOrbe.Desconhecida("novo"))
        assertThat(r(4, e)).isEqualTo(Comando.Executar(AcaoDaOrbe.LerTela))
    }

    @Test
    fun `Desconhecida e RodarFluxo sem Fluxos nao fazem nada`() {
        assertThat(r(1, ef(a1 = AcaoDaOrbe.Desconhecida("novo")))).isEqualTo(Comando.Nada)
        assertThat(r(2, ef(a2 = AcaoDaOrbe.RodarFluxo("f")))).isEqualTo(Comando.Nada)
    }

    @Test
    fun `o gancho de fluxo vale tambem para o 1 toque, e fluxo invalido nao executa a acao por baixo`() {
        assertThat(r(1, fluxos = { if (it == 1) ResolucaoDeFluxo.Disparar("f1") else null })).isEqualTo(Comando.RodarFluxo("f1"))
        assertThat(r(2, fluxos = { ResolucaoDeFluxo.Invalido("f2") })).isEqualTo(Comando.FluxoIncompleto)
        // no overflow o fluxo do 3 vale mesmo com a acao do 3 em Nenhuma
        assertThat(r(5, ef(a3 = AcaoDaOrbe.Nenhuma), fluxos = { if (it == 3) ResolucaoDeFluxo.Disparar("f3") else null })).isEqualTo(Comando.RodarFluxo("f3"))
    }
}
