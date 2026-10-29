package com.keyflow.fluxos

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Validação do fluxo (FLX-13..15): cadeia de tipos, entrada só na posição 0, limites e o seletor de etapas compatíveis. */
class ValidadorFluxoTest {

    private fun fluxo(vararg etapas: Etapa) = Fluxo(id = "f1", nome = "Teste", etapas = etapas.toList())

    private fun problemas(vararg etapas: Etapa) = ValidadorFluxo.validar(fluxo(*etapas)).problemas

    // ── validar ─────────────────────────────────────────────────────────────

    @Test
    fun `Capturar, Visao, Inserir e valido`() {
        val v = ValidadorFluxo.validar(fluxo(Etapa.Capturar, Etapa.Visao(), Etapa.Inserir))
        assertThat(v.valido).isTrue()
        assertThat(v.problemas).isEmpty()
        assertThat(v.avisos).isEmpty()
    }

    @Test
    fun `os tres fluxos de exemplo da spec sao validos`() {
        assertThat(ValidadorFluxo.validar(fluxo(Etapa.Ditar(), Etapa.Perguntar(), Etapa.Falar())).valido).isTrue()
        assertThat(ValidadorFluxo.validar(fluxo(Etapa.Ditar(), Etapa.Agente(), Etapa.Inserir)).valido).isTrue()
    }

    @Test
    fun `Capturar seguido de Perguntar e invalido e o motivo e que falta um texto`() {
        val p = problemas(Etapa.Capturar, Etapa.Perguntar())
        assertThat(p).containsExactly(Problema(indice = 1, motivo = Motivo.PRECISA_DE_TEXTO))
    }

    @Test
    fun `Ditar seguido de Visao e invalido e o motivo e que falta uma imagem`() {
        assertThat(problemas(Etapa.Ditar(), Etapa.Visao())).containsExactly(Problema(1, Motivo.PRECISA_DE_IMAGEM))
    }

    @Test
    fun `Ditar e Capturar so passam na posicao 0`() {
        assertThat(problemas(Etapa.Ditar(), Etapa.Ditar())).containsExactly(Problema(1, Motivo.SO_PODE_SER_A_PRIMEIRA))
        assertThat(problemas(Etapa.Ditar(), Etapa.Capturar)).containsExactly(Problema(1, Motivo.SO_PODE_SER_A_PRIMEIRA))
        assertThat(problemas(Etapa.Capturar, Etapa.Ditar())).containsExactly(Problema(1, Motivo.SO_PODE_SER_A_PRIMEIRA))
        assertThat(problemas(Etapa.Capturar, Etapa.Visao(), Etapa.Capturar)).containsExactly(Problema(2, Motivo.SO_PODE_SER_A_PRIMEIRA))
    }

    @Test
    fun `a primeira etapa precisa nao ter entrada`() {
        assertThat(problemas(Etapa.Perguntar())).containsExactly(Problema(0, Motivo.PRECISA_DE_TEXTO))
        assertThat(problemas(Etapa.Visao())).containsExactly(Problema(0, Motivo.PRECISA_DE_IMAGEM))
        assertThat(problemas(Etapa.Inserir, Etapa.Falar())).containsExactly(Problema(0, Motivo.PRECISA_DE_TEXTO))
    }

    @Test
    fun `fluxo sem etapas e invalido`() {
        val v = ValidadorFluxo.validar(fluxo())
        assertThat(v.valido).isFalse()
        assertThat(v.problemas).containsExactly(Problema(indice = null, motivo = Motivo.SEM_ETAPAS))
    }

    @Test
    fun `oito etapas passam e nove nao`() {
        val oito = Array(8) { if (it == 0) Etapa.Ditar() else Etapa.Perguntar() }
        assertThat(ValidadorFluxo.validar(fluxo(*oito)).valido).isTrue()
        val nove = Array(9) { if (it == 0) Etapa.Ditar() else Etapa.Perguntar() }
        val v = ValidadorFluxo.validar(fluxo(*nove))
        assertThat(v.valido).isFalse()
        assertThat(v.problemas).containsExactly(Problema(indice = 8, motivo = Motivo.ETAPAS_DEMAIS))
    }

    @Test
    fun `etapa desconhecida invalida o fluxo e e apontada`() {
        val v = ValidadorFluxo.validar(fluxo(Etapa.Ditar(), Etapa.Desconhecida("grafo"), Etapa.Inserir))
        assertThat(v.valido).isFalse()
        assertThat(v.problemas).containsExactly(Problema(1, Motivo.ETAPA_DESCONHECIDA))
    }

    @Test
    fun `etapa desconhecida na primeira posicao nao gera um segundo problema na seguinte`() {
        assertThat(problemas(Etapa.Desconhecida("x"), Etapa.Perguntar())).containsExactly(Problema(0, Motivo.ETAPA_DESCONHECIDA))
    }

    @Test
    fun `remover a etapa do meio deixa o fluxo incompleto com o motivo na etapa seguinte`() {
        // Capturar -> Visão -> Inserir, sem a Visão: Capturar -> Inserir
        assertThat(problemas(Etapa.Capturar, Etapa.Inserir)).containsExactly(Problema(1, Motivo.PRECISA_DE_TEXTO))
    }

    @Test
    fun `cada problema aparece uma vez por etapa e em ordem`() {
        val p = problemas(Etapa.Capturar, Etapa.Perguntar(), Etapa.Ditar(), Etapa.Visao())
        assertThat(p.map { it.indice }).isInOrder()
        assertThat(p.map { it.indice }).containsExactly(1, 2, 3).inOrder()
    }

    // ── avisos ──────────────────────────────────────────────────────────────

    @Test
    fun `fluxo que termina em Perguntar e valido com aviso de resultado descartado`() {
        val v = ValidadorFluxo.validar(fluxo(Etapa.Ditar(), Etapa.Perguntar()))
        assertThat(v.valido).isTrue()
        assertThat(v.avisos).containsExactly(Aviso.SEM_SAIDA)
    }

    @Test
    fun `terminar em Ditar, Capturar, Visao ou Perguntar avisa`() {
        for (ultima in listOf(Etapa.Ditar(), Etapa.Capturar)) {
            assertThat(ValidadorFluxo.validar(fluxo(ultima)).avisos).containsExactly(Aviso.SEM_SAIDA)
        }
        assertThat(ValidadorFluxo.validar(fluxo(Etapa.Capturar, Etapa.Visao())).avisos).containsExactly(Aviso.SEM_SAIDA)
        assertThat(ValidadorFluxo.validar(fluxo(Etapa.Ditar(), Etapa.Perguntar())).avisos).containsExactly(Aviso.SEM_SAIDA)
    }

    @Test
    fun `terminar em Falar, Inserir ou Agente nao avisa`() {
        for (ultima in listOf(Etapa.Falar(), Etapa.Inserir, Etapa.Agente())) {
            assertThat(ValidadorFluxo.validar(fluxo(Etapa.Ditar(), ultima)).avisos).isEmpty()
        }
    }

    @Test
    fun `fluxo invalido nao recebe o aviso de sem saida`() {
        assertThat(ValidadorFluxo.validar(fluxo(Etapa.Perguntar())).avisos).isEmpty()
        assertThat(ValidadorFluxo.validar(fluxo()).avisos).isEmpty()
    }

    // ── compatíveis ─────────────────────────────────────────────────────────

    private fun habilitadas(ultima: Tipo?) =
        ValidadorFluxo.compativeis(ultima).filter { it.habilitada }.map { it.tipo }

    private fun motivoDe(ultima: Tipo?, tipo: TipoEtapa) =
        ValidadorFluxo.compativeis(ultima).first { it.tipo == tipo }.motivo

    @Test
    fun `fluxo vazio so aceita etapas de entrada`() {
        assertThat(habilitadas(null)).containsExactly(TipoEtapa.DITAR, TipoEtapa.CAPTURAR)
        assertThat(motivoDe(null, TipoEtapa.PERGUNTAR)).isEqualTo(Motivo.PRECISA_DE_TEXTO)
        assertThat(motivoDe(null, TipoEtapa.VISAO)).isEqualTo(Motivo.PRECISA_DE_IMAGEM)
    }

    @Test
    fun `depois de um texto aceita Perguntar, Falar, Inserir e Agente`() {
        assertThat(habilitadas(Tipo.TEXTO))
            .containsExactly(TipoEtapa.PERGUNTAR, TipoEtapa.AGENTE, TipoEtapa.FALAR, TipoEtapa.INSERIR)
        assertThat(motivoDe(Tipo.TEXTO, TipoEtapa.VISAO)).isEqualTo(Motivo.PRECISA_DE_IMAGEM)
        assertThat(motivoDe(Tipo.TEXTO, TipoEtapa.DITAR)).isEqualTo(Motivo.SO_PODE_SER_A_PRIMEIRA)
        assertThat(motivoDe(Tipo.TEXTO, TipoEtapa.CAPTURAR)).isEqualTo(Motivo.SO_PODE_SER_A_PRIMEIRA)
    }

    @Test
    fun `depois de uma imagem so aceita Visao`() {
        assertThat(habilitadas(Tipo.IMAGEM)).containsExactly(TipoEtapa.VISAO)
        for (tipo in listOf(TipoEtapa.PERGUNTAR, TipoEtapa.AGENTE, TipoEtapa.FALAR, TipoEtapa.INSERIR)) {
            assertThat(motivoDe(Tipo.IMAGEM, tipo)).isEqualTo(Motivo.PRECISA_DE_TEXTO)
        }
        assertThat(motivoDe(Tipo.IMAGEM, TipoEtapa.DITAR)).isEqualTo(Motivo.SO_PODE_SER_A_PRIMEIRA)
    }

    @Test
    fun `etapa habilitada nao tem motivo e a lista vem agrupada na ordem do seletor`() {
        val lista = ValidadorFluxo.compativeis(Tipo.TEXTO)
        assertThat(lista.filter { it.habilitada }.map { it.motivo }.toSet()).containsExactly(null)
        assertThat(lista.filter { !it.habilitada }.all { it.motivo != null }).isTrue()
        assertThat(lista.map { it.tipo }).containsExactly(
            TipoEtapa.DITAR, TipoEtapa.CAPTURAR, TipoEtapa.VISAO, TipoEtapa.PERGUNTAR, TipoEtapa.AGENTE, TipoEtapa.FALAR, TipoEtapa.INSERIR,
        ).inOrder()
        assertThat(lista.map { it.grupo }.distinct()).containsExactly(GrupoDeEtapa.ENTRADA, GrupoDeEtapa.PROCESSAR, GrupoDeEtapa.SAIDA).inOrder()
    }

    @Test
    fun `os motivos tem texto traduzivel`() {
        assertThat(Motivo.PRECISA_DE_TEXTO.texto).isEqualTo("Precisa de um texto antes")
        assertThat(Motivo.PRECISA_DE_IMAGEM.texto).isEqualTo("Precisa de uma imagem antes")
        assertThat(Motivo.SO_PODE_SER_A_PRIMEIRA.texto).isEqualTo("Só pode ser a primeira etapa")
        assertThat(Aviso.SEM_SAIDA.texto).isEqualTo("O resultado será descartado: termine com Falar, Inserir ou Agente")
    }
}
