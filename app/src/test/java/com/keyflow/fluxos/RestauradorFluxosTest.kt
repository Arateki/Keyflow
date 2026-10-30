package com.keyflow.fluxos

import com.google.common.truth.Truth.assertThat
import com.keyflow.orbe.LugarDaOrbe
import org.junit.Test

/** Fluxos padrão e "Restaurar fluxos padrão" (FLX-40..44). */
class RestauradorFluxosTest {

    private val ouvir get() = PadroesFluxo.CATALOGO[0]
    private val ler get() = PadroesFluxo.CATALOGO[1]
    private val agente get() = PadroesFluxo.CATALOGO[2]

    private fun daSemente(builtinId: String) = PadroesFluxo.semear().first { it.padrao == builtinId }

    private fun pessoa(id: String, nome: String = "Meu $id") = Fluxo(id, nome, padrao = null, etapas = listOf(Etapa.Ditar(), Etapa.Inserir))

    private fun ids(): () -> String {
        var n = 0
        return { "novo${++n}" }
    }

    private fun restaurar(config: ConfigFluxos) = RestauradorFluxos.restaurar(config, ids())

    private fun nomes(config: ConfigFluxos) = config.fluxos.map { it.nome }

    // ── catálogo e semente ──────────────────────────────────────────────────

    @Test
    fun `o catalogo tem os tres fluxos da spec, na ordem`() {
        assertThat(PadroesFluxo.CATALOGO.map { it.builtinId }).containsExactly("ouvir-responder", "ler-tela-colar", "agente-colar").inOrder()
        assertThat(PadroesFluxo.CATALOGO.map { it.nome })
            .containsExactly("Ouvir e responder", "Ler a tela e colar", "Pedir ao agente e colar").inOrder()
        assertThat(ouvir.etapas.map { it.tipo }).containsExactly(TipoEtapa.DITAR, TipoEtapa.PERGUNTAR, TipoEtapa.FALAR).inOrder()
        assertThat(ler.etapas.map { it.tipo }).containsExactly(TipoEtapa.CAPTURAR, TipoEtapa.VISAO, TipoEtapa.INSERIR).inOrder()
        assertThat(agente.etapas.map { it.tipo }).containsExactly(TipoEtapa.DITAR, TipoEtapa.AGENTE, TipoEtapa.INSERIR).inOrder()
        assertThat(PadroesFluxo.PADROES_VERSAO).isEqualTo(1)
    }

    @Test
    fun `os padrao usam o modelo da aba e a instrucao da visao vazia`() {
        for (padrao in PadroesFluxo.CATALOGO) {
            assertThat(padrao.etapas.mapNotNull { it.modelo }).isEmpty()
        }
        assertThat((ler.etapas[1] as Etapa.Visao).instrucao).isNull()
        assertThat((ouvir.etapas[1] as Etapa.Perguntar).sistema).contains("curta")
        assertThat((ouvir.etapas[1] as Etapa.Perguntar).sistema).contains("sem listas")
    }

    @Test
    fun `a semente tem os tres fluxos validos com id estavel s-builtinId`() {
        val semente = PadroesFluxo.semear()
        assertThat(semente.map { it.id }).containsExactly("s-ouvir-responder", "s-ler-tela-colar", "s-agente-colar").inOrder()
        assertThat(semente.map { it.padrao }).containsExactly("ouvir-responder", "ler-tela-colar", "agente-colar").inOrder()
        assertThat(semente.map { ValidadorFluxo.validar(it).valido }).containsExactly(true, true, true)
        assertThat(PadroesFluxo.semear()).isEqualTo(semente) // estável a cada chamada
    }

    @Test
    fun `os tres padrao passam no validador sem aviso`() {
        for (padrao in PadroesFluxo.CATALOGO) {
            val v = ValidadorFluxo.validar(Fluxo("x", padrao.nome, padrao.builtinId, padrao.etapas))
            assertThat(v.valido).isTrue()
            assertThat(v.avisos).isEmpty()
        }
    }

    @Test
    fun `a semente nao traz gatilho e a previa dela e vazia`() {
        val config = ConfigFluxos(PadroesFluxo.semear(), emptyList())
        assertThat(config.gatilhos).isEmpty()
        val previa = RestauradorFluxos.previa(config)
        assertThat(previa.vazia).isTrue()
        assertThat(previa.nadaAGravar).isTrue()
        assertThat(previa).isEqualTo(PreviaRestaurar(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `cada padrao traz a dica de atalho sugerido e nenhum atalho`() {
        assertThat(ouvir.dica).isEqualTo(DicaDeAtalho.DOIS_TOQUES)
        assertThat(ler.dica).isEqualTo(DicaDeAtalho.TRES_TOQUES)
        assertThat(agente.dica).isEqualTo(DicaDeAtalho.BOTAO_DO_TECLADO)
        assertThat(PadroesFluxo.dicaDe(daSemente("agente-colar"))).isEqualTo(DicaDeAtalho.BOTAO_DO_TECLADO)
        assertThat(PadroesFluxo.dicaDe(pessoa("p"))).isNull()
        // renomear ou editar não tira a dica: o `padrao` fica
        assertThat(PadroesFluxo.dicaDe(daSemente("ler-tela-colar").copy(nome = "Outro nome"))).isEqualTo(DicaDeAtalho.TRES_TOQUES)
    }

    @Test
    fun `o nome do padrao nao renomeado sai traduzido e o digitado fica cru`() {
        assertThat(PadroesFluxo.nomeExibido(daSemente("ouvir-responder"))).isEqualTo("Ouvir e responder") // PT nos testes
        assertThat(PadroesFluxo.nomeExibido(daSemente("ouvir-responder").copy(nome = "Meu nome"))).isEqualTo("Meu nome")
        assertThat(PadroesFluxo.nomeExibido(pessoa("p", "Ouvir e responder"))).isEqualTo("Ouvir e responder")
    }

    // ── restaurar ───────────────────────────────────────────────────────────

    @Test
    fun `padrao apagado volta sem gatilho, com id novo e na posicao do catalogo`() {
        val fluxos = listOf(daSemente("ouvir-responder"), daSemente("agente-colar"))
        val config = restaurar(ConfigFluxos(fluxos, emptyList()))
        assertThat(config.fluxos.map { it.padrao }).containsExactly("ouvir-responder", "ler-tela-colar", "agente-colar").inOrder()
        val recriado = config.fluxos[1]
        assertThat(recriado.id).isEqualTo("novo1")
        assertThat(recriado.id).isNotEqualTo("s-ler-tela-colar")
        assertThat(recriado.nome).isEqualTo(ler.nome)
        assertThat(recriado.etapas).isEqualTo(ler.etapas)
        assertThat(config.gatilhos).isEmpty()
    }

    @Test
    fun `o gatilho do padrao apagado nao e religado ao recriado`() {
        val ligado = Gatilho(Superficie.Barra("b1"), "s-ler-tela-colar") // o fluxo foi apagado: o gatilho ficou órfão
        val outro = Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), "s-ouvir-responder")
        val original = ConfigFluxos(listOf(daSemente("ouvir-responder"), daSemente("agente-colar")), listOf(ligado, outro))
        val config = restaurar(original)
        assertThat(config.gatilhos).containsExactly(ligado, outro).inOrder()
        assertThat(config.gatilhosDe(config.fluxos[1].id)).isEmpty()
    }

    @Test
    fun `padrao apagado entra no inicio quando nenhum antecessor existe`() {
        val config = restaurar(ConfigFluxos(listOf(pessoa("p1"), daSemente("agente-colar"), pessoa("p2")), emptyList()))
        // "ouvir" e "ler" não existem: ambos entram no início, na ordem do catálogo
        assertThat(config.fluxos.map { it.padrao }).containsExactly("ouvir-responder", "ler-tela-colar", null, "agente-colar", null).inOrder()
    }

    @Test
    fun `padrao apagado entra logo depois do ultimo antecessor presente`() {
        val config = restaurar(ConfigFluxos(listOf(daSemente("ouvir-responder"), pessoa("p1"), pessoa("p2"), daSemente("agente-colar")), emptyList()))
        assertThat(config.fluxos.map { it.padrao }).containsExactly("ouvir-responder", "ler-tela-colar", null, null, "agente-colar").inOrder()
    }

    @Test
    fun `lista vazia recupera os tres na ordem do catalogo`() {
        val config = restaurar(ConfigFluxos(emptyList(), emptyList()))
        assertThat(config.fluxos.map { it.padrao }).containsExactly("ouvir-responder", "ler-tela-colar", "agente-colar").inOrder()
        assertThat(config.fluxos.map { it.id }).containsExactly("novo1", "novo2", "novo3").inOrder()
        assertThat(config.gatilhos).isEmpty()
    }

    @Test
    fun `padrao editado volta ao original mantendo id, posicao e gatilhos`() {
        val editado = daSemente("ouvir-responder").copy(
            nome = "Meu ouvir",
            etapas = listOf(Etapa.Ditar(ModeloRef("OPENAI", "whisper-1")), Etapa.Perguntar("outro sistema", ModeloRef("LOCAL", "gemma")), Etapa.Inserir),
        )
        val gatilhos = listOf(Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), editado.id), Gatilho(Superficie.Barra("b1"), editado.id))
        val original = ConfigFluxos(listOf(pessoa("p1"), editado, daSemente("ler-tela-colar")), gatilhos)

        val config = restaurar(original)

        assertThat(config.fluxos[1]).isEqualTo(daSemente("ouvir-responder"))
        assertThat(config.fluxos[1].id).isEqualTo("s-ouvir-responder")
        assertThat(config.fluxos.map { it.padrao }.take(3)).containsExactly(null, "ouvir-responder", "ler-tela-colar").inOrder()
        assertThat(config.gatilhos).isEqualTo(gatilhos)
    }

    @Test
    fun `padrao so com o modelo trocado volta para o da aba`() {
        val trocado = daSemente("ler-tela-colar").copy(etapas = listOf(Etapa.Capturar, Etapa.Visao(modelo = ModeloRef("GOOGLE", "gemini")), Etapa.Inserir))
        assertThat(restaurar(ConfigFluxos(listOf(trocado), emptyList())).fluxos.first { it.padrao == "ler-tela-colar" }.etapas).isEqualTo(ler.etapas)
    }

    @Test
    fun `padrao igual ao catalogo deixa a config identica`() {
        val config = ConfigFluxos(
            PadroesFluxo.semear() + pessoa("p1"),
            listOf(Gatilho(Superficie.Orbe(LugarDaOrbe.NOTIFICACAO, 3), "p1")),
        )
        assertThat(restaurar(config)).isEqualTo(config)
        assertThat(RestauradorFluxos.previa(config).vazia).isTrue()
    }

    @Test
    fun `fluxo da pessoa fica identico e na mesma ordem relativa`() {
        val a = pessoa("a")
        val b = pessoa("b").copy(etapas = listOf(Etapa.Capturar, Etapa.Visao("ler"), Etapa.Falar()))
        val c = pessoa("c")
        val config = restaurar(ConfigFluxos(listOf(a, daSemente("ler-tela-colar").copy(nome = "x"), b, c), emptyList()))
        assertThat(config.fluxos.filter { it.padrao == null }).containsExactly(a, b, c).inOrder()
    }

    @Test
    fun `um fluxo da pessoa com o nome de um padrao nao e tocado`() {
        val imitacao = pessoa("p", "Ouvir e responder")
        val config = restaurar(ConfigFluxos(listOf(imitacao), emptyList()))
        assertThat(config.fluxos).contains(imitacao)
        assertThat(config.fluxos.count { it.padrao == "ouvir-responder" }).isEqualTo(1) // o padrão foi recriado ao lado
    }

    @Test
    fun `restaurar duas vezes e restaurar uma`() {
        val editado = daSemente("agente-colar").copy(etapas = listOf(Etapa.Ditar(), Etapa.Falar()))
        val inicial = ConfigFluxos(listOf(pessoa("p1"), editado), listOf(Gatilho(Superficie.Barra("b1"), editado.id)))
        val uma = restaurar(inicial)
        val duas = restaurar(uma)
        assertThat(duas).isEqualTo(uma)
        assertThat(RestauradorFluxos.previa(uma).nadaAGravar).isTrue()
        assertThat(RestauradorFluxos.previa(uma).vazia).isTrue() // aqui cabia tudo: já estão como no original
    }

    @Test
    fun `dois fluxos com o mesmo padrao so o primeiro e reescrito`() {
        val primeiro = daSemente("ouvir-responder").copy(nome = "Um")
        val segundo = primeiro.copy(id = "dup", nome = "Dois")
        val config = restaurar(ConfigFluxos(listOf(primeiro, segundo), emptyList()))
        assertThat(config.fluxos.first { it.id == primeiro.id }.nome).isEqualTo(ouvir.nome)
        assertThat(config.fluxos.first { it.id == "dup" }).isEqualTo(segundo)
    }

    @Test
    fun `os ids recriados nunca repetem um id em uso, mesmo com gerador ruim`() {
        val config = RestauradorFluxos.restaurar(ConfigFluxos(listOf(pessoa("x")), emptyList())) { "x" }
        assertThat(config.fluxos.map { it.id }).containsNoDuplicates()
        assertThat(config.fluxos).hasSize(4)
    }

    @Test
    fun `o id padrao do restaurar e aleatorio de 8 hex`() {
        val ids = RestauradorFluxos.restaurar(ConfigFluxos(emptyList(), emptyList())).fluxos.map { it.id }
        assertThat(ids).containsNoDuplicates()
        for (id in ids) assertThat(id).matches("[0-9a-f]{8}")
    }

    // ── limite de 20 fluxos ─────────────────────────────────────────────────

    @Test
    fun `com a lista no limite o padrao apagado nao volta e a previa avisa`() {
        val da_pessoa = (1..LIMITES.FLUXOS).map { pessoa("p$it") }
        val config = ConfigFluxos(da_pessoa, emptyList())
        assertThat(restaurar(config)).isEqualTo(config)
        val previa = RestauradorFluxos.previa(config)
        assertThat(previa.semEspaco).containsExactly(ouvir.nome, ler.nome, agente.nome).inOrder()
        assertThat(previa.recriar).isEmpty()
        // Nada a gravar, mas os padrão NÃO "já estão como no original": faltam três. A confirmação não pode dizer o contrário.
        assertThat(previa.nadaAGravar).isTrue()
        assertThat(previa.vazia).isFalse()
    }

    @Test
    fun `so volta o que cabe, na ordem do catalogo`() {
        val config = ConfigFluxos((1..18).map { pessoa("p$it") }, emptyList())
        val restaurado = restaurar(config)
        assertThat(restaurado.fluxos).hasSize(LIMITES.FLUXOS)
        assertThat(restaurado.fluxos.mapNotNull { it.padrao }).containsExactly("ouvir-responder", "ler-tela-colar").inOrder()
        assertThat(RestauradorFluxos.previa(config).semEspaco).containsExactly(agente.nome)
        assertThat(RestauradorFluxos.previa(config).vazia).isFalse()
        assertThat(RestauradorFluxos.previa(config).nadaAGravar).isFalse() // recria dois
    }

    @Test
    fun `reescrever um padrao existente cabe sempre, mesmo no limite`() {
        val editado = daSemente("ouvir-responder").copy(nome = "Meu")
        val config = ConfigFluxos(listOf(editado) + (1 until LIMITES.FLUXOS).map { pessoa("p$it") }, emptyList())
        val restaurado = restaurar(config)
        assertThat(restaurado.fluxos).hasSize(LIMITES.FLUXOS)
        assertThat(restaurado.fluxos[0]).isEqualTo(daSemente("ouvir-responder"))
    }

    // ── prévia ──────────────────────────────────────────────────────────────

    @Test
    fun `a previa lista o que sera recriado e o que sera reescrito`() {
        val editado = daSemente("ouvir-responder").copy(nome = "Meu ouvir")
        val config = ConfigFluxos(listOf(editado, daSemente("agente-colar")), emptyList())
        val previa = RestauradorFluxos.previa(config)
        assertThat(previa.recriar).containsExactly(ler.nome)
        assertThat(previa.reescrever).containsExactly("Meu ouvir")
        assertThat(previa.vazia).isFalse()
        // a prévia não escolhe ids nem muda a config: é só leitura
        assertThat(config.fluxos).hasSize(2)
    }

    @Test
    fun `a previa concorda com o que o restaurar faz`() {
        val config = ConfigFluxos(listOf(daSemente("ler-tela-colar").copy(etapas = emptyList()), pessoa("p")), emptyList())
        val previa = RestauradorFluxos.previa(config)
        val depois = restaurar(config)
        assertThat(previa.recriar.size + config.fluxos.size).isEqualTo(depois.fluxos.size)
        assertThat(previa.recriar).containsExactly(ouvir.nome, agente.nome).inOrder()
        assertThat(previa.reescrever).containsExactly(ler.nome)
    }
}
