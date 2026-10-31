package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.keyflow.auth.ConnectionState
import com.keyflow.data.Settings
import com.keyflow.fluxos.AbaDaEtapa
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.ModeloOferecido
import com.keyflow.fluxos.OpcaoDeModelo
import com.keyflow.fluxos.abaDaEtapa
import com.keyflow.fluxos.agenteSemFerramentas
import com.keyflow.fluxos.opcoesDeModelo
import com.keyflow.ui.common.collectAsStateOnMain

/**
 * O dado que o seletor de modelo por etapa lê: o mesmo que as abas das capacidades mostram (design §11.3) — os modelos locais já
 * instalados e os provedores conectados. Não baixa nem pede chave. [conectados] vem por capacidade (um provedor pode estar
 * conectado numa aba e não noutra: Custom tem endereço por capacidade).
 */
class FonteDaTela(
    private val settings: Settings,
    private val instalados: Map<AbaDaEtapa, List<ModeloOferecido>>,
    private val conectados: Map<AbaDaEtapa, Set<String>>,
    /** Os modelos de nuvem que as abas já conhecem (os da conta, ou os de fábrica), por aba e origem. */
    private val catalogo: Map<AbaDaEtapa, Map<String, List<ModeloOferecido>>> = emptyMap(),
    /** Origens do Agente com chave de API (só ela dá ferramentas); `null` = não sabe, vale só a origem. */
    private val comChaveDeApi: Set<String>? = null,
) : FonteDeModelos {
    override fun opcoes(etapa: Etapa): List<OpcaoDeModelo> {
        val aba = abaDaEtapa(etapa) ?: return emptyList()
        return opcoesDeModelo(etapa, settings, instalados[aba].orEmpty(), conectados[aba].orEmpty(), catalogo[aba].orEmpty())
    }

    override fun semFerramentas(etapa: Etapa): Boolean = agenteSemFerramentas(etapa, settings, comChaveDeApi)
}

private fun LocalModelsUi.oferecidos(): List<ModeloOferecido> =
    items.filter { it.status == LocalModelStatus.INSTALLED }.map { ModeloOferecido(it.spec.id, it.spec.displayName) }

/** O que a tela sabe de um provedor: conectado por algum método, se tem chave de API, e os modelos da conta. */
internal class Provedor(val conectado: Boolean, val chave: Boolean, val modelos: List<ModeloOferecido>) {
    companion object { val AUSENTE = Provedor(false, false, emptyList()) }
}

@Composable
private fun rememberProvedor(acao: AuthActions, padrao: List<AccountModel>): Provedor {
    val estado by remember(acao) { acao.state() }.collectAsStateOnMain(ConnectionState.Disconnected)
    val chave by remember(acao) { acao.apiKeyFlow() }.collectAsStateOnMain(null)
    val conectado = estado is ConnectionState.Connected || !chave.isNullOrBlank()
    // Os modelos da conta só se pedem com conexão (a lista de fábrica entra se a conta não responde): o mesmo dado da aba.
    val modelos by produceState(padrao, acao, conectado) {
        value = padrao
        if (conectado) value = runCatching { acao.listModels() }.getOrDefault(emptyList()).ifEmpty { padrao }
    }
    return Provedor(conectado, !chave.isNullOrBlank(), modelos.map { ModeloOferecido(it.id, it.displayName) })
}

/**
 * Monta a [FonteDaTela] a partir do que a [ConfigScreen] já tem das abas; refaz quando o estado muda. A disponibilidade segue o
 * que cada aba exige: Raciocínio e Visão aceitam login ou chave; Transcrição (OpenAI e Groq) e Voz (OpenAI, Google, ElevenLabs)
 * só funcionam com a **chave de API** (o login por assinatura não serve, como no `FalaPanel`); o Agente só usa ferramentas com chave.
 */
@Composable
internal fun rememberFonteDeModelos(
    settings: Settings,
    transcricao: LocalModelsUi,
    raciocinio: LocalModelsUi,
    visao: LocalModelsUi,
    fala: LocalModelsUi,
    openAi: AuthActions,
    anthropic: AuthActions,
    google: AuthActions,
    transcricaoOpenAi: AuthActions,
    groq: AuthActions,
    elevenLabs: AuthActions,
): FonteDeModelos {
    val oai = rememberProvedor(openAi, OPENAI_MODELS_DEFAULT)
    val ant = rememberProvedor(anthropic, ANTHROPIC_MODELS_DEFAULT)
    val goo = rememberProvedor(google, GOOGLE_MODELS_DEFAULT)
    val oaiTx = rememberProvedor(transcricaoOpenAi, emptyList())
    val grq = rememberProvedor(groq, emptyList())
    val eleven = rememberProvedor(elevenLabs, emptyList())
    val oaiChave = oai.chave || oaiTx.chave
    return remember(settings, transcricao, raciocinio, visao, fala, oai.conectado, oai.chave, ant.conectado, ant.chave, goo.conectado,
        goo.chave, oaiChave, grq.chave, grq.conectado, eleven.chave, oai.modelos, ant.modelos, goo.modelos) {
        fonteDaTela(settings, mapOf(
            AbaDaEtapa.TRANSCRICAO to transcricao.oferecidos(), AbaDaEtapa.RACIOCINIO to raciocinio.oferecidos(),
            AbaDaEtapa.VISAO to visao.oferecidos(), AbaDaEtapa.VOZ to fala.oferecidos(),
        ), oai, ant, goo, oaiTx, grq, eleven)
    }
}

/** A decisão pura (testável): o que cada aba oferece, dado o que a tela sabe dos provedores. */
internal fun fonteDaTela(
    settings: Settings,
    instalados: Map<AbaDaEtapa, List<ModeloOferecido>>,
    oai: Provedor, ant: Provedor, goo: Provedor, oaiTx: Provedor, grq: Provedor, eleven: Provedor,
): FonteDaTela {
    val oaiChave = oai.chave || oaiTx.chave
        val nuvem = mapOf("OPENAI" to oai.modelos, "ANTHROPIC" to ant.modelos, "GOOGLE" to goo.modelos)
    return FonteDaTela(
            settings = settings,
            instalados = instalados,
            conectados = mapOf(
                AbaDaEtapa.TRANSCRICAO to buildSet {
                    if (oaiChave) add("OPENAI"); if (grq.chave || grq.conectado) add("GROQ"); if (settings.customEndpoint.isNotBlank()) add("CUSTOM")
                },
                AbaDaEtapa.RACIOCINIO to buildSet {
                    if (oai.conectado) add("OPENAI"); if (ant.conectado) add("ANTHROPIC"); if (goo.conectado) add("GOOGLE")
                    if (settings.reasoningCustomEndpoint.isNotBlank()) add("CUSTOM")
                },
                AbaDaEtapa.VISAO to buildSet { if (oai.conectado) add("OPENAI"); if (ant.conectado) add("ANTHROPIC"); if (goo.conectado) add("GOOGLE") },
                AbaDaEtapa.VOZ to buildSet { if (oai.chave) add("OPENAI"); if (goo.chave) add("GOOGLE"); if (eleven.chave) add("ELEVENLABS") },
            ),
            catalogo = mapOf(
                AbaDaEtapa.RACIOCINIO to nuvem,
                AbaDaEtapa.VISAO to nuvem,
                AbaDaEtapa.TRANSCRICAO to mapOf("OPENAI" to OPENAI_MODELS.map { ModeloOferecido(it, it) }),
            ),
            comChaveDeApi = buildSet {
                if (oai.chave) add("OPENAI"); if (ant.chave) add("ANTHROPIC"); if (goo.chave) add("GOOGLE")
            },
    )
}
