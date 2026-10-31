package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.FalaSource
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.fluxos.AbaDaEtapa
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.ModeloOferecido
import com.keyflow.fluxos.ModeloRef
import com.keyflow.fluxos.OpcaoDeModelo
import org.junit.Test

/** O seletor de modelo por etapa lê o mesmo que as abas: modelos da conta e a disponibilidade pelo método de autenticação. */
class FonteDaTelaTest {
    private val gpt = ModeloOferecido("gpt-4o", "GPT-4o")
    private val conectado = Provedor(conectado = true, chave = false, modelos = listOf(gpt))
    private val comChave = Provedor(conectado = true, chave = true, modelos = listOf(gpt))
    private val settings = Settings(reasoningSource = ReasoningSource.OPENAI)

    private fun fonte(oai: Provedor = Provedor.AUSENTE, goo: Provedor = Provedor.AUSENTE) =
        fonteDaTela(settings, emptyMap(), oai, Provedor.AUSENTE, goo, Provedor.AUSENTE, Provedor.AUSENTE, Provedor.AUSENTE)

    @Test
    fun `os modelos da conta aparecem como escolha e a referencia salva nao vira indisponivel`() {
        val etapa = Etapa.Perguntar(modelo = ModeloRef("OPENAI", "gpt-4o"))
        val opcoes = fonte(oai = conectado).opcoes(etapa)
        val escolha = opcoes.filterIsInstance<OpcaoDeModelo.Escolha>().single { it.ref == ModeloRef("OPENAI", "gpt-4o") }
        assertThat(escolha.modelo).isEqualTo(gpt)
        assertThat(escolha.selecionada).isTrue()
        assertThat(opcoes.filterIsInstance<OpcaoDeModelo.Indisponivel>()).isEmpty()
    }

    @Test
    fun `Falar com login sem chave nao oferece o provedor`() {
        val oauth = fonte(oai = conectado)
        val opcoes = oauth.opcoes(Etapa.Falar())
        assertThat(opcoes.filterIsInstance<OpcaoDeModelo.Conectar>().map { it.origemNome }).contains("OpenAI")
        assertThat(opcoes.filterIsInstance<OpcaoDeModelo.Escolha>().map { it.ref.origem }).doesNotContain("OPENAI")
        // com a chave de API a voz da OpenAI passa a valer
        val chave = fonte(oai = comChave).opcoes(Etapa.Falar())
        assertThat(chave.filterIsInstance<OpcaoDeModelo.Escolha>().map { it.ref.origem }).contains("OPENAI")
    }

    @Test
    fun `Agente com login por assinatura avisa que nao usa ferramentas`() {
        val agente = Etapa.Agente(modelo = ModeloRef("OPENAI", "gpt-4o"))
        assertThat(fonte(oai = conectado).semFerramentas(agente)).isTrue()
        assertThat(fonte(oai = comChave).semFerramentas(agente)).isFalse()
    }

    @Test
    fun `Raciocinio aceita login ou chave`() {
        val origens = fonte(oai = conectado).opcoes(Etapa.Perguntar()).filterIsInstance<OpcaoDeModelo.Escolha>().map { it.ref.origem }
        assertThat(origens).contains("OPENAI")
        assertThat(FalaSource.entries).isNotEmpty() // a aba Voz segue a sua própria regra, testada acima
    }
}
